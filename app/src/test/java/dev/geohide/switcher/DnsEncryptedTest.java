package dev.geohide.switcher;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class DnsEncryptedTest {
    private static final DnsUpstream.Protector PASS=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return true;}public boolean tcp(Socket s){return true;}};
    private TlsFixture fixture(boolean http)throws Exception{return new TlsFixture(getClass().getResourceAsStream("/dns-test.p12"),http);}
    private DnsUpstream upstream(TlsFixture s,DnsProfiles.Transport mode,String host,boolean trust){
        return new DnsUpstream(PASS,new DnsProfiles.Selection(s.profile(host),mode),new String[]{"127.0.0.1"},s.server.getLocalPort(),trust?s.client():null,trust?s.context.getSocketFactory():null);
    }
    @Test public void profilesAreIsolatedAndAiCannotUsePlainDns(){
        assertEquals(5,DnsProfiles.ALL.length);DnsProfiles.Profile ai=DnsProfiles.find("dns_ai");assertFalse(ai.supports(DnsProfiles.Transport.UDP));
        assertEquals(DnsProfiles.Transport.DOT,DnsProfiles.normalize(ai,"UDP"));assertEquals("dns.dns-ai.ru",ai.dohHost());
        try{new DnsProfiles.Selection(ai,DnsProfiles.Transport.UDP);fail();}catch(IllegalArgumentException expected){}
        assertEquals("geohide",DnsProfiles.find("invalid").id);
        for(DnsProfiles.Profile p:DnsProfiles.ALL){assertTrue(p.dohUrl.startsWith("https://"));assertTrue(p.addresses.length>=2);}
    }
    @Test public void httpsDnsAndTlsDnsRoundTrip()throws Exception{
        for(DnsProfiles.Transport mode:new DnsProfiles.Transport[]{DnsProfiles.Transport.DOH,DnsProfiles.Transport.DOT})
            try(TlsFixture s=fixture(mode==DnsProfiles.Transport.DOH);DnsUpstream up=upstream(s,mode,"localhost",true)){
                byte[] q=DnsPackets.probe(93);byte[] r=up.resolve(q);assertTrue(DnsPackets.matches(q,r));assertEquals(8,r[r.length-1]);assertTrue(s.requests.get()>0);
            }
    }
    @Test public void wrongHostnameAndUntrustedCertificateAreRejected()throws Exception{
        for(DnsProfiles.Transport mode:new DnsProfiles.Transport[]{DnsProfiles.Transport.DOH,DnsProfiles.Transport.DOT}){
            try(TlsFixture s=fixture(mode==DnsProfiles.Transport.DOH);DnsUpstream up=upstream(s,mode,"wrong.example",true)){
                try{up.resolve(DnsPackets.probe(94));fail("Hostname verification bypassed");}catch(IOException expected){}assertEquals(0,s.requests.get());
            }
            try(TlsFixture s=fixture(mode==DnsProfiles.Transport.DOH);DnsUpstream up=upstream(s,mode,"localhost",false)){
                try{up.resolve(DnsPackets.probe(95));fail("Certificate validation bypassed");}catch(IOException expected){}assertEquals(0,s.requests.get());
            }
        }
    }
    @Test public void rejectsHttpRedirectWrongMimeAndOversizedBodies()throws Exception{
        for(int kind=0;kind<3;kind++)try(TlsFixture s=fixture(true);DnsUpstream up=upstream(s,DnsProfiles.Transport.DOH,"localhost",true)){
            if(kind==0){s.httpCode=302;s.redirect="https://example.com/dns-query";}else if(kind==1)s.contentType="text/html";else s.oversized=true;
            try{up.resolve(DnsPackets.probe(96));fail();}catch(IOException expected){}assertEquals(1,s.requests.get());
        }
    }
    @Test public void cancelBlockedEncryptedRequestClosesConnections()throws Exception{
        for(DnsProfiles.Transport mode:new DnsProfiles.Transport[]{DnsProfiles.Transport.DOH,DnsProfiles.Transport.DOT})
            try(TlsFixture s=fixture(mode==DnsProfiles.Transport.DOH);DnsUpstream up=upstream(s,mode,"localhost",true)){
                s.hold=true;ExecutorService pool=Executors.newSingleThreadExecutor();
                try{Future<byte[]> f=pool.submit(()->up.resolve(DnsPackets.probe(97)));s.awaitRequest();long start=System.nanoTime();up.close();
                    try{f.get(1500,TimeUnit.MILLISECONDS);fail();}catch(ExecutionException expected){}assertTrue(System.nanoTime()-start<1_500_000_000L);assertTrue(up.sockets.isEmpty());assertTrue(up.calls.isEmpty());
                }finally{pool.shutdownNow();}
            }
    }
}
