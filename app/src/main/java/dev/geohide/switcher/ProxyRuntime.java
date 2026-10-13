package dev.geohide.switcher;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import org.json.*;
import hev.htproxy.TProxyService;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
final class ProxyRuntime implements AutoCloseable {
    static volatile String testProbeUrl;
    static volatile JSONObject testDnsServer;
    static volatile String testCertificate;
    final DnsVpnService owner;
    private final TProxyService nativeTunnel=new TProxyService();
    private volatile boolean closed;
    private CoreProcess core;private ParcelFileDescriptor tun;private File nativeConfig;
    private volatile OkHttpClient client;
    private volatile Call checking;
    private final java.util.Set<Closeable> probes=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private synchronized void track(Closeable c)throws IOException{if(closed){c.close();throw new IOException("Cancelled");}probes.add(c);}
    int port;String user,password,type;
    ProxyRuntime(DnsVpnService owner){this.owner=owner;}
    synchronized boolean alive(){return !closed && core!=null && core.process.isAlive() && nativeTunnel.TProxyIsRunning();}
    OkHttpClient http(){return client;}
    long[] stats(){return nativeTunnel.TProxyGetStats();}
    void start()throws Exception{
        JSONObject outbound=ProxyConfig.parse(ProxyStore.load(owner));if(BuildConfig.DEBUG && testCertificate!=null && outbound.has("tls"))outbound.getJSONObject("tls").put("certificate",testCertificate);type=outbound.getString("type");
        String host=outbound.getString("server");String sni=host;
        android.net.Network net=NetworkProfiles.physical(owner);if(net==null)throw new IOException("Нет физической сети");
        InetAddress[] addresses=net.getAllByName(host);if(addresses.length==0)throw new UnknownHostException();
        InetAddress selected=addresses[0];for(InetAddress address:addresses)if(address instanceof Inet4Address){selected=address;break;}
        outbound.put("server",selected.getHostAddress());
        if(outbound.has("tls") && outbound.getJSONObject("tls").optString("server_name").isEmpty())outbound.getJSONObject("tls").put("server_name",sni);
        try(Socket socket=new Socket()){track(socket);if(!NetworkProfiles.protector(owner).tcp(socket))throw new IOException("Не удалось выбрать физическую сеть");socket.connect(new InetSocketAddress(selected,outbound.getInt("server_port")),2500);}
        try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        user="geohide";byte[] token=new byte[24];new java.security.SecureRandom().nextBytes(token);password=Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        JSONObject config=ProxyConfig.client(outbound,port,user,password);
        if(BuildConfig.DEBUG && testDnsServer!=null)config.put("dns",new JSONObject().put("servers",new JSONArray().put(testDnsServer)).put("final",testDnsServer.getString("tag")));
        if(closed)throw new IOException("Cancelled");CoreProcess created=CoreProcess.start(owner,config);synchronized(this){if(closed){created.close();throw new IOException("Cancelled");}core=created;}
        long deadline=System.nanoTime()+5_000_000_000L;boolean ready=false;
        while(!closed && System.nanoTime()<deadline){if(!core.process.isAlive())throw new IOException("Процесс ядра завершился");try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),200);ready=true;break;}catch(IOException e){Thread.sleep(60);}}
        if(!ready)throw new IOException("Ядро не открыло локальный прокси");
        OkHttpClient http=new OkHttpClient.Builder().proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",port))).proxyAuthenticator((route,response)->response.request().header("Proxy-Authorization")!=null?null:response.request().newBuilder().header("Proxy-Authorization",Credentials.basic(user,password)).build()).addNetworkInterceptor(chain->{Request request=chain.request();if(!request.isHttps())request=request.newBuilder().header("Proxy-Authorization",Credentials.basic(user,password)).build();return chain.proceed(request);}).connectTimeout(3,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(9,TimeUnit.SECONDS).followRedirects(false).build();
        synchronized(this){if(closed)throw new IOException("Cancelled");client=http;}
        String url=BuildConfig.DEBUG && testProbeUrl!=null?testProbeUrl:"https://www.gstatic.com/generate_204";
        synchronized(this){if(closed)throw new IOException("Cancelled");checking=http.newCall(new Request.Builder().url(url).build());}try(Response response=checking.execute()){if(response.code()<100 || response.code()>599)throw new IOException("Нет ответа через прокси");}finally{checking=null;}
        dnsProbe();
        synchronized(this){if(closed)throw new IOException("Cancelled");
            tun=owner.new Builder().setSession("GeoHide VPN · "+type).setMtu(1400).addAddress("10.77.77.2",32).addAddress("fd77:77::2",128).addDnsServer("10.77.77.1").addRoute("0.0.0.0",0).addRoute("::",0).addDisallowedApplication(owner.getPackageName()).establish();
            if(tun==null)throw new IOException("Android отклонил VPN");
            nativeConfig=File.createTempFile("tun-",".yml",owner.getNoBackupFilesDir());
            String yaml="tunnel:\n  mtu: 1400\n  ipv4: 10.77.77.2\n  ipv6: 'fd77:77::2'\nsocks5:\n  address: 127.0.0.1\n  port: "+port+"\n  udp: 'udp'\n  username: '"+user+"'\n  password: '"+password+"'\nmisc:\n  max-session-count: 512\n  connect-timeout: 5000\n  log-level: error\n  log-file: null\n";
            try(FileOutputStream out=new FileOutputStream(nativeConfig)){out.write(yaml.getBytes(StandardCharsets.UTF_8));}
            if(!nativeTunnel.TProxyStartService(nativeConfig.getAbsolutePath(),tun.getFd()))throw new IOException("Нативный туннель не запущен");
        }
        Thread.sleep(150);if(!alive())throw new IOException("Нативный туннель завершился");
    }
    private void dnsProbe()throws Exception{
        byte[] query=DnsPackets.probe(0x7011);
        try(Socket control=new Socket();DatagramSocket udp=new DatagramSocket()){
            track(control);track(udp);control.connect(new InetSocketAddress("127.0.0.1",port),1500);control.setSoTimeout(3000);udp.setSoTimeout(6000);
            DataOutputStream out=new DataOutputStream(control.getOutputStream());DataInputStream in=new DataInputStream(control.getInputStream());
            out.write(new byte[]{5,1,2});out.flush();if(in.readUnsignedByte()!=5 || in.readUnsignedByte()!=2)throw new IOException("SOCKS не поддержал аутентификацию");
            byte[] u=user.getBytes(StandardCharsets.UTF_8),p=password.getBytes(StandardCharsets.UTF_8);out.writeByte(1);out.writeByte(u.length);out.write(u);out.writeByte(p.length);out.write(p);out.flush();if(in.readUnsignedByte()!=1 || in.readUnsignedByte()!=0)throw new IOException("SOCKS отказал в аутентификации");
            out.write(new byte[]{5,3,0,1,127,0,0,1});out.writeShort(udp.getLocalPort());out.flush();if(in.readUnsignedByte()!=5 || in.readUnsignedByte()!=0)throw new IOException("SOCKS UDP недоступен");in.readUnsignedByte();int addressType=in.readUnsignedByte();byte[] address;if(addressType==1)address=new byte[4];else if(addressType==4)address=new byte[16];else throw new IOException("Неверный SOCKS UDP адрес");in.readFully(address);int relayPort=in.readUnsignedShort();if(relayPort==0)throw new IOException("Неверный порт SOCKS UDP");InetAddress relay=InetAddress.getByAddress(address);if(relay.isAnyLocalAddress())relay=InetAddress.getByName("127.0.0.1");
            udp.connect(relay,relayPort);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream frame=new DataOutputStream(bytes);frame.write(new byte[]{0,0,0,1,10,77,77,1});frame.writeShort(53);frame.write(query);byte[] request=bytes.toByteArray();udp.send(new DatagramPacket(request,request.length,relay,relayPort));
            byte[] buffer=new byte[4096];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);udp.receive(packet);if(packet.getLength()<4 || buffer[0]!=0 || buffer[1]!=0 || buffer[2]!=0)throw new IOException("Неверный SOCKS UDP заголовок");int header=buffer[3]==1?10:buffer[3]==4?22:-1;if(header<0 || packet.getLength()<=header)throw new IOException("Неверный UDP-ответ");byte[] dns=Arrays.copyOfRange(buffer,header,packet.getLength());if(!DnsPackets.matches(query,dns) || (dns[3]&15)!=0 || DnsPackets.u16(dns,6)==0)throw new IOException("DNS через прокси не отвечает");
        }
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;Call pending=checking;OkHttpClient http=client;IoCleanup.run(()->{if(pending!=null)pending.cancel();if(http!=null){http.dispatcher().cancelAll();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}});for(Closeable probe:probes)try{probe.close();}catch(IOException ignored){}probes.clear();
        nativeTunnel.TProxyStopService();if(tun!=null)try{tun.close();}catch(IOException ignored){}tun=null;
        if(core!=null)core.close();core=null;if(nativeConfig!=null)nativeConfig.delete();
    }
}
