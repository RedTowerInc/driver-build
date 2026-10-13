package dev.geohide.switcher;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import okhttp3.*;

/** Cancel-safe, provider-isolated upstream. TLS trust and hostname verification are never disabled. */
class DnsUpstream implements Closeable {
    interface Protector { boolean udp(DatagramSocket s); boolean tcp(Socket s); }
    static final String[] SERVERS=DnsProfiles.ALL[0].addresses;
    final Set<Closeable> sockets=ConcurrentHashMap.newKeySet();
    final Set<Call> calls=ConcurrentHashMap.newKeySet();
    private static final ScheduledThreadPoolExecutor LIMITS=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"DNS-IO-limit");t.setDaemon(true);return t;});
    static {LIMITS.setRemoveOnCancelPolicy(true);}
    private final Protector protector;
    private final String[] servers;
    private final int port;
    private final DnsProfiles.Selection selection;
    private final OkHttpClient http;
    private final SSLSocketFactory tlsFactory;
    private final AtomicInteger preferred=new AtomicInteger();
    private volatile boolean closed;
    private DnsUpstream[] auto;
    private volatile DnsProfiles.Transport actual;
    private final ArrayBlockingQueue<TlsConnection> idle=new ArrayBlockingQueue<>(4);
    private final Semaphore tlsSlots=new Semaphore(4);
    private volatile int generation;
    final AtomicInteger tlsCreated=new AtomicInteger(),tlsReused=new AtomicInteger();
    private static final class TlsConnection {
        final Socket raw;final SSLSocket tls;final String ip;final int generation;volatile long used;
        TlsConnection(Socket raw,SSLSocket tls,String ip,int generation){this.raw=raw;this.tls=tls;this.ip=ip;this.generation=generation;used=System.nanoTime();}
    }
    DnsProfiles.Transport actualTransport(){return selection.transport==DnsProfiles.Transport.AUTO?actual:selection.transport;}
    synchronized void prefer(DnsProfiles.Transport t){if(auto!=null && t!=null && auto[1].selection.transport==t){DnsUpstream first=auto[0];auto[0]=auto[1];auto[1]=first;}}
    DnsUpstream(Protector p,DnsProfiles.Selection selection,DnsUpstream[] fixtures){this(p,selection);for(DnsUpstream child:auto)child.close();auto=fixtures.clone();}

    volatile String server="";
    // Preserved UDP constructor for the previous protocol tests and local packet fixture.
    DnsUpstream(Protector p,String[] servers,int port){this(p,new DnsProfiles.Selection(DnsProfiles.ALL[0],DnsProfiles.Transport.UDP),servers,port,null,null);}
    DnsUpstream(Protector p,DnsProfiles.Selection selection){this(p,selection,selection.profile.addresses,selection.transport==DnsProfiles.Transport.DOT?853:53,null,null);}
    // Trust overrides are package-private fixtures. Production always passes null.
    DnsUpstream(Protector p,DnsProfiles.Selection selection,String[] servers,int port,OkHttpClient fixture,SSLSocketFactory tlsFixture){
        this.protector=p;this.selection=selection;this.servers=servers.clone();this.port=port;
        tlsFactory=tlsFixture!=null?tlsFixture:(SSLSocketFactory)SSLSocketFactory.getDefault();
        if(selection.transport==DnsProfiles.Transport.DOH){
            OkHttpClient.Builder b=fixture!=null?fixture.newBuilder():new OkHttpClient.Builder();
            http=b.proxy(Proxy.NO_PROXY).socketFactory(new ProtectedFactory()).dns(host->{
                if(!host.equalsIgnoreCase(selection.profile.dohHost()))throw new UnknownHostException("Unexpected DNS hostname");
                List<InetAddress> result=new ArrayList<>();for(String ip:this.servers)result.add(InetAddress.getByName(ip));return result;
            }).connectTimeout(1500,TimeUnit.MILLISECONDS).readTimeout(2500,TimeUnit.MILLISECONDS)
                .writeTimeout(2500,TimeUnit.MILLISECONDS).callTimeout(6,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(2,30,TimeUnit.SECONDS)).build();
        }else http=null;
        if(selection.transport==DnsProfiles.Transport.AUTO){
            DnsProfiles.Transport first=selection.profile.initial==DnsProfiles.Transport.DOH?DnsProfiles.Transport.DOH:DnsProfiles.Transport.DOT;
            DnsProfiles.Transport second=first==DnsProfiles.Transport.DOH?DnsProfiles.Transport.DOT:DnsProfiles.Transport.DOH;
            auto=new DnsUpstream[]{new DnsUpstream(p,new DnsProfiles.Selection(selection.profile,first)),new DnsUpstream(p,new DnsProfiles.Selection(selection.profile,second))};
        }

    }
    private synchronized void track(Closeable s)throws IOException{if(closed){s.close();throw new IOException("Cancelled");}sockets.add(s);}
    private final class ManagedSocket extends Socket {
        @Override public void close()throws IOException{try{super.close();}finally{sockets.remove(this);}}
    }
    private Socket socket(InetAddress local,int localPort)throws IOException{
        Socket s=new ManagedSocket();try{track(s);s.bind(new InetSocketAddress(local,localPort));
            if(!protector.tcp(s))throw new IOException("VPN socket protection failed");return s;
        }catch(IOException e){s.close();throw e;}
    }
    private class ProtectedFactory extends SocketFactory {
        public Socket createSocket()throws IOException{return socket(null,0);}
        private Socket connect(Socket s,InetAddress host,int p)throws IOException{try{s.connect(new InetSocketAddress(host,p),1500);return s;}catch(IOException e){s.close();throw e;}}
        public Socket createSocket(String h,int p)throws IOException{return connect(createSocket(),InetAddress.getByName(h),p);}
        public Socket createSocket(InetAddress h,int p)throws IOException{return connect(createSocket(),h,p);}
        public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return connect(socket(l,lp),InetAddress.getByName(h),p);}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return connect(socket(l,lp),h,p);}
    }
    byte[] resolve(byte[] query)throws IOException{
        if(closed)throw new IOException("Cancelled");
        if(auto!=null){
            IOException last=null;DnsUpstream[] candidates; synchronized(this){candidates=auto.clone();}
            for(DnsUpstream child:candidates){if(closed)throw new IOException("Cancelled");try{byte[] r=child.resolve(query);actual=child.actualTransport();server=child.server;prefer(actual);return r;}catch(IOException e){last=e;}}
            throw new IOException("Оба шифрованных режима не ответили",last);
        }
        if(selection.transport==DnsProfiles.Transport.DOH)return doh(query);
        int first=Math.floorMod(preferred.get(),servers.length);
        long deadline=System.nanoTime()+6_000_000_000L;
        IOException last=null;
        for(int n=0;n<servers.length && !closed && System.nanoTime()<deadline;n++){
            int i=(first+n)%servers.length;
            try {
                byte[] r=selection.transport==DnsProfiles.Transport.DOT?dot(query,servers[i]):udp(query,servers[i]);
                if((r[2]&2)!=0)r=tcp(query,servers[i]);
                if((r[3]&15)==2 || (r[3]&15)==5)throw new IOException("DNS refused the query");
                preferred.set(i);server=servers[i]+(selection.transport==DnsProfiles.Transport.DOT?" · TLS":" · UDP");return r;
            }catch(IOException e){last=e;}
        }
        throw new IOException(closed?"Cancelled":selection.profile.name+" не отвечает"+(last instanceof SSLException?": ошибка TLS-сертификата":""),last);
    }
    private byte[] udp(byte[] q,String ip)throws IOException{
        DatagramSocket s=new DatagramSocket();
        try{track(s);if(!protector.udp(s))throw new IOException("VPN socket protection failed");
            s.setSoTimeout(700);s.connect(InetAddress.getByName(ip),port);s.send(new DatagramPacket(q,q.length));
            byte[] r=new byte[65507];DatagramPacket p=new DatagramPacket(r,r.length);s.receive(p);
            byte[] result=Arrays.copyOf(r,p.getLength());check(q,result);return result;
        }finally{sockets.remove(s);s.close();}
    }
    private byte[] readDns(Socket s,byte[] q)throws IOException{
        DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeShort(q.length);out.write(q);out.flush();
        DataInputStream in=new DataInputStream(s.getInputStream());int len=in.readUnsignedShort();
        if(len<12 || len>65507)throw new IOException("Invalid DNS length");byte[] r=new byte[len];in.readFully(r);check(q,r);
        if((r[2]&2)!=0)throw new IOException("Truncated DNS stream response");return r;
    }
    private byte[] tcp(byte[] q,String ip)throws IOException{
        Socket s=socket(null,0);ScheduledFuture<?> limit=LIMITS.schedule(()->{try{s.close();}catch(IOException ignored){}},2500,TimeUnit.MILLISECONDS);
        try{s.connect(new InetSocketAddress(ip,port),700);s.setSoTimeout(700);return readDns(s,q);}
        finally{limit.cancel(false);s.close();}
    }
    private void discard(TlsConnection c){sockets.remove(c.tls);try{c.tls.close();}catch(IOException ignored){}try{c.raw.close();}catch(IOException ignored){}}
    private static final class StaleConnection extends IOException {StaleConnection(IOException cause){super("Stale TLS connection",cause);}}
    private byte[] dot(byte[] q,String ip)throws IOException{try{return dotAttempt(q,ip);}catch(StaleConnection e){if(closed)throw e;return dotAttempt(q,ip);}}
    private byte[] dotAttempt(byte[] q,String ip)throws IOException{
        boolean acquired=false,reused=false;TlsConnection conn=null;ScheduledFuture<?> limit=null;boolean success=false;
        try{
            try{acquired=tlsSlots.tryAcquire(1500,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Cancelled",e);}
            if(!acquired)throw new SocketTimeoutException("TLS pool busy");
            if(closed)throw new IOException("Cancelled");
            TlsConnection candidate;
            while((candidate=idle.poll())!=null){
                if(candidate.generation==generation && candidate.ip.equals(ip) && !candidate.tls.isClosed() && System.nanoTime()-candidate.used<30_000_000_000L){conn=candidate;reused=true;tlsReused.incrementAndGet();break;}discard(candidate);
            }
            final Socket raw=conn==null?socket(null,0):conn.raw;
            limit=LIMITS.schedule(()->{try{raw.close();}catch(IOException ignored){}},3500,TimeUnit.MILLISECONDS);
            if(conn==null){
                int startedGeneration=generation;SSLSocket tls=null;
                try{raw.connect(new InetSocketAddress(ip,port),1500);raw.setSoTimeout(1800);
                    tls=(SSLSocket)tlsFactory.createSocket(raw,selection.profile.tlsHost,port,true);track(tls);
                    SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");params.setServerNames(Collections.singletonList(new SNIHostName(selection.profile.tlsHost)));tls.setSSLParameters(params);tls.startHandshake();
                    conn=new TlsConnection(raw,tls,ip,startedGeneration);tlsCreated.incrementAndGet();
                }catch(IOException e){if(tls!=null){sockets.remove(tls);try{tls.close();}catch(IOException ignored){}}raw.close();throw e;}
            }
            byte[] result=readDns(conn.tls,q);success=true;return result;
        }catch(IOException e){if(reused && !(e instanceof SocketTimeoutException) && !closed)throw new StaleConnection(e);throw e;}finally{
            if(limit!=null)limit.cancel(false);
            if(conn!=null){conn.used=System.nanoTime();synchronized(this){if(!success || closed || conn.generation!=generation || !idle.offer(conn))discard(conn);}}
            if(acquired)tlsSlots.release();
        }
    }
    private byte[] doh(byte[] q)throws IOException{
        Request request=new Request.Builder().url(selection.profile.dohUrl).header("Accept","application/dns-message")
            .post(RequestBody.create(q,MediaType.get("application/dns-message"))).build();
        Call call=http.newCall(request);
        synchronized(this){if(closed)throw new IOException("Cancelled");calls.add(call);}
        try(Response response=call.execute()){
            if(response.code()!=200 || response.body()==null)throw new IOException("DNS HTTPS: HTTP "+response.code());
            String type=response.header("Content-Type","").split(";",2)[0].trim();
            if(!type.equalsIgnoreCase("application/dns-message"))throw new IOException("Unexpected DNS HTTPS content type");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[2048];int n;
            try(InputStream in=response.body().byteStream()){while((n=in.read(buf))!=-1){if(out.size()+n>65507)throw new IOException("DNS response too large");out.write(buf,0,n);}}
            byte[] r=out.toByteArray();check(q,r);if((r[2]&2)!=0 || (r[3]&15)==2 || (r[3]&15)==5)throw new IOException("DNS HTTPS refused or truncated response");
            server=selection.profile.dohHost()+" · HTTPS";return r;
        }finally{calls.remove(call);}
    }
    private static void check(byte[] q,byte[] r)throws IOException{if(!DnsPackets.matches(q,r))throw new IOException("Invalid DNS response");}
    private void abortSockets(){
        Closeable[] captured=sockets.toArray(new Closeable[0]);sockets.clear();
        // Close raw transports first so blocked readers wake immediately. SSL wrappers
        // are finalized on the I/O thread; Conscrypt close_notify must not run on UI.
        for(Closeable socket:captured)if(!(socket instanceof SSLSocket))IoCleanup.close(socket);
        IoCleanup.run(()->{for(Closeable socket:captured)if(socket instanceof SSLSocket)IoCleanup.close(socket);});
    }
    synchronized void networkChanged(){
        generation++;idle.clear();if(auto!=null)for(DnsUpstream child:auto)child.networkChanged();abortSockets();if(http!=null)IoCleanup.run(()->http.connectionPool().evictAll());
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;idle.clear();if(auto!=null)for(DnsUpstream child:auto)child.close();
        Call[] pending=calls.toArray(new Call[0]);calls.clear();abortSockets();
        IoCleanup.run(()->{for(Call c:pending)c.cancel();if(http!=null){http.dispatcher().cancelAll();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}});
    }
}
