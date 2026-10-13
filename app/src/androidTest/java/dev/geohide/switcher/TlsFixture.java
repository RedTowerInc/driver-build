package dev.geohide.switcher;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import okhttp3.OkHttpClient;

/** Loopback-only TLS server and test trust store; never included in the installed application. */
final class TlsFixture implements Closeable {
    final SSLContext context;
    final X509TrustManager trust;
    final SSLServerSocket server;
    final ExecutorService workers=Executors.newCachedThreadPool();
    final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    final AtomicInteger requests=new AtomicInteger();
    volatile boolean hold;
    volatile int httpCode=200;
    volatile String contentType="application/dns-message";
    volatile String redirect="";
    volatile boolean oversized;
    private final boolean http;
    TlsFixture(InputStream keys,boolean http)throws Exception{
        this.http=http;KeyStore k=KeyStore.getInstance("PKCS12");k.load(keys,"dns-test-only".toCharArray());keys.close();
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(k,"dns-test-only".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(k);trust=(X509TrustManager)tm.getTrustManagers()[0];
        context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        server=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,30,InetAddress.getByName("127.0.0.1"));
        workers.execute(()->{while(!server.isClosed())try{Socket s=server.accept();if(DnsVpnService.instance!=null)DnsVpnService.instance.protect(s);clients.add(s);workers.execute(()->handle(s));}catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}});
    }
    OkHttpClient client(){return new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(),trust).build();}
    DnsProfiles.Profile profile(String tlsHost){return new DnsProfiles.Profile("fixture","TLS fixture","Local test only",tlsHost,"https://"+tlsHost+":"+server.getLocalPort()+"/dns-query","https://localhost/",new String[]{"127.0.0.1"},false,DnsProfiles.Transport.DOH);}
    private void handle(Socket s){
        try(Socket sock=s){sock.setSoTimeout(30000);DataInputStream in=new DataInputStream(sock.getInputStream());OutputStream out=sock.getOutputStream();
            do { byte[] q;
            if(http){String first=in.readLine();if(first==null)return;int len=0;String line;
                while((line=in.readLine())!=null && !line.isEmpty()){if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(line.substring(15).trim());}
                if(len<12 || len>65507)return;q=new byte[len];in.readFully(q);
            }else{int len=in.readUnsignedShort();q=new byte[len];in.readFully(q);}
            requests.incrementAndGet();if(hold){while(!server.isClosed())Thread.sleep(50);return;}
            byte[] r=response(q);if(oversized)r=new byte[66000];
            if(http){String header="HTTP/1.1 "+httpCode+" Test\r\nContent-Type: "+contentType+"\r\nContent-Length: "+r.length+"\r\nConnection: close\r\n";
                if(!redirect.isEmpty())header+="Location: "+redirect+"\r\n";out.write((header+"\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.write(r);
            }else{DataOutputStream dos=new DataOutputStream(out);dos.writeShort(r.length);dos.write(r);}out.flush();
            } while(!http && !server.isClosed());
        }catch(Exception ignored){}finally{clients.remove(s);}
    }
    static byte[] response(byte[] q){return response(q,8);}
    static byte[] response(byte[] q,int last){int end=DnsPackets.questionEnd(q);int type=DnsPackets.u16(q,end-4);int size=type==1?4:type==28?16:0;
        byte[] r=Arrays.copyOf(q,end+(size>0?12+size:0));r[2]=(byte)0x81;r[3]=(byte)0x80;DnsPackets.p16(r,6,size>0?1:0);DnsPackets.p16(r,8,0);DnsPackets.p16(r,10,0);
        if(size>0){int n=end;r[n]=(byte)0xc0;r[n+1]=0x0c;DnsPackets.p16(r,n+2,type);r[n+5]=1;r[n+9]=60;r[n+11]=(byte)size;
            if(type==1){r[n+12]=(byte)203;r[n+14]=113;}else{r[n+12]=0x20;r[n+13]=1;r[n+14]=0x0d;r[n+15]=(byte)0xb8;}
            r[n+11+size]=(byte)last;}return r;
    }
    void awaitRequest()throws Exception{long end=System.currentTimeMillis()+4000;while(requests.get()==0 && System.currentTimeMillis()<end)Thread.sleep(10);if(requests.get()==0)throw new IOException("Test request did not reach TLS server");}
    @Override public void close()throws IOException{server.close();for(Socket s:clients)s.close();workers.shutdownNow();}
}
