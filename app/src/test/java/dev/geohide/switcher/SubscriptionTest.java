package dev.geohide.switcher;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public class SubscriptionTest {
    static final String KEY="vless://00000000-0000-4000-8000-000000000001@example.com:443?security=tls&sni=example.com&type=raw#Portal%20TCP";
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test public void plaintextAndBase64FilterWithoutWeakeningTls()throws Exception{
        String text=KEY+"\n"+KEY+"\n"+KEY.replace("type=raw","type=xhttp")+"\n"+KEY.replace("security=tls","security=none")+"\n"+KEY.replace("security=tls","security=tls&allowInsecure=1");
        ProxySubscription.Result plain=ProxySubscription.parse(bytes(text));assertEquals(1,plain.entries.size());assertEquals(1,plain.duplicates);assertEquals(3,plain.skippedCount());
        assertTrue(plain.entries.get(0).label.contains("TCP"));assertFalse(plain.entries.get(0).label.contains("00000000"));assertTrue(plain.entries.get(0).label.contains("Portal TCP"));assertFalse(plain.summary().contains("vless://"));
        String encoded=Base64.getEncoder().encodeToString(bytes(text));assertEquals(plain.entries.size(),ProxySubscription.parse(bytes(encoded)).entries.size());
        String safe=KEY.replace("security=tls","security=tls&insecure=1");try{ProxyConfig.parse(safe);fail();}catch(ProxyConfig.Invalid expected){}
    }
    @Test public void malformedOversizedAndUnsupportedBodiesHaveSafeErrors()throws Exception{
        for(String body:new String[]{"", "<html>login secret-token</html>","{\"outbounds\":[]}","not a subscription",KEY.replace("type=raw","type=xhttp")}){
            try{ProxySubscription.parse(bytes(body));fail();}catch(Exception e){assertFalse(ProxySubscription.error(e).contains("secret-token"));assertFalse(ProxySubscription.error(e).contains("vless://"));}
        }
        try{ProxySubscription.parse(new byte[ProxySubscription.MAX_BYTES+1]);fail();}catch(ProxySubscription.Problem expected){}
        try{ProxySubscription.parse(new byte[]{(byte)0xc3,0x28});fail();}catch(ProxySubscription.Problem expected){}
        StringBuilder many=new StringBuilder();for(int i=0;i<=ProxySubscription.MAX_PROFILES;i++)many.append(KEY).append('\n');try{ProxySubscription.parse(bytes(many.toString()));fail();}catch(ProxySubscription.Problem expected){}
    }
    @Test public void urlValidationAndSizeBoundDoNotLeakCredentials()throws Exception{
        assertEquals("https://example.com/sub?token=private",ProxySubscription.https("\u00a0https://example.com/sub?token=private\u00a0"));
        for(String url:new String[]{"http://example.com/sub","https://user:private@example.com/sub","https://example.com:99999/sub"})try{ProxySubscription.https(url);fail();}catch(Exception e){assertFalse(ProxySubscription.error(e).contains("private"));}
        try{ProxySubscription.read(new ByteArrayInputStream(new byte[ProxySubscription.MAX_BYTES+1]));fail();}catch(ProxySubscription.Problem expected){}
    }
    @Test public void httpsFetchRedirectAndCertificateVerification()throws Exception{
        try(TlsFixture tls=new TlsFixture(getClass().getResourceAsStream("/dns-test.p12"),false);HttpFixture server=new HttpFixture(tls.context,bytes(KEY))){
            try(SubscriptionLoader loader=new SubscriptionLoader(tls.client())){assertEquals(1,loader.load(server.url()).entries.size());}
            try(SubscriptionLoader loader=new SubscriptionLoader(new okhttp3.OkHttpClient.Builder().dns(host->Collections.singletonList(InetAddress.getByName("127.0.0.1"))).build())){loader.load(server.url());fail("Accepted untrusted certificate");}catch(SSLException expected){}
            server.location="http://example.com/sub";try(SubscriptionLoader loader=new SubscriptionLoader(tls.client())){loader.load(server.url());fail();}catch(ProxySubscription.Problem expected){}
            server.location=server.url();try(SubscriptionLoader loader=new SubscriptionLoader(tls.client())){loader.load(server.url());fail();}catch(ProxySubscription.Problem expected){}
            server.location=null;server.code=403;try(SubscriptionLoader loader=new SubscriptionLoader(tls.client())){loader.load(server.url());fail();}catch(ProxySubscription.Problem expected){assertTrue(expected.getMessage().contains("403"));}
            server.code=200;server.body=new byte[ProxySubscription.MAX_BYTES+1];try(SubscriptionLoader loader=new SubscriptionLoader(tls.client())){loader.load(server.url());fail();}catch(ProxySubscription.Problem expected){}
        }
    }
    @Test public void cancellationInterruptsPendingFetch()throws Exception{
        try(TlsFixture tls=new TlsFixture(getClass().getResourceAsStream("/dns-test.p12"),false);HttpFixture server=new HttpFixture(tls.context,bytes(KEY));SubscriptionLoader loader=new SubscriptionLoader(tls.client())){
            server.hold=true;ExecutorService executor=Executors.newSingleThreadExecutor();Future<?> future=executor.submit(()->{try{loader.load(server.url());throw new AssertionError("Completed cancelled fetch");}catch(IOException expected){}catch(Exception e){throw new RuntimeException(e);}});
            try{assertTrue(server.request.await(4,TimeUnit.SECONDS));loader.close();future.get(2,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
        }
    }
    static final class HttpFixture implements AutoCloseable {
        final SSLServerSocket server;final ExecutorService worker=Executors.newCachedThreadPool();final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final CountDownLatch request=new CountDownLatch(1);
        volatile byte[] body;volatile String location;volatile int code=200;volatile boolean hold;
        HttpFixture(SSLContext context,byte[] bytes)throws Exception{body=bytes;server=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,16,InetAddress.getByName("127.0.0.1"));worker.execute(()->{while(!server.isClosed())try{Socket s=server.accept();sockets.add(s);worker.execute(()->{try(Socket socket=s){socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line;while((line=reader.readLine())!=null && !line.isEmpty()){}request.countDown();while(hold && !server.isClosed())Thread.sleep(20);byte[] payload=body;String header="HTTP/1.1 "+(location==null?code:302)+" Test\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n"+(location==null?"":"Location: "+location+"\r\n")+"\r\n";socket.getOutputStream().write(bytes(header));socket.getOutputStream().write(payload);socket.getOutputStream().flush();}catch(Exception ignored){}finally{sockets.remove(s);}});}catch(IOException ignored){}});}
        String url(){return "https://localhost:"+server.getLocalPort()+"/subscription";}
        @Override public void close()throws Exception{server.close();for(Socket s:sockets)s.close();worker.shutdownNow();}
    }
}
