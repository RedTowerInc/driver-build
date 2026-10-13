package dev.geohide.switcher;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;
public class UpgradeTest {
    private static final DnsUpstream.Protector PASS=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return true;}public boolean tcp(Socket s){return true;}};
    private static class Stub extends DnsUpstream {
        int requests;boolean fail;final DnsProfiles.Transport transport;
        Stub(DnsProfiles.Transport t,boolean fail){super(PASS,new DnsProfiles.Selection(DnsProfiles.find("dns_ai"),t));transport=t;this.fail=fail;}
        @Override byte[] resolve(byte[] q)throws IOException{requests++;if(fail)throw new SocketTimeoutException();server="fixture · "+transport;return TlsFixture.response(q);}
    }
    @Test public void autoFallsBackAndRemembersWithoutAnotherProvider()throws Exception{
        Stub tls=new Stub(DnsProfiles.Transport.DOT,true),https=new Stub(DnsProfiles.Transport.DOH,false);
        try(DnsUpstream auto=new DnsUpstream(PASS,new DnsProfiles.Selection(DnsProfiles.find("dns_ai"),DnsProfiles.Transport.AUTO),new DnsUpstream[]{tls,https})){
            byte[] q=DnsPackets.probe(77);assertTrue(DnsPackets.matches(q,auto.resolve(q)));assertEquals(DnsProfiles.Transport.DOH,auto.actualTransport());assertEquals(1,tls.requests);assertEquals(1,https.requests);
            auto.resolve(q);assertEquals(1,tls.requests);assertEquals(2,https.requests);https.fail=true;tls.fail=false;auto.resolve(q);assertEquals(DnsProfiles.Transport.DOT,auto.actualTransport());
        }
    }
    @Test public void tlsConnectionsAreReusedAndClearedOnNetworkChange()throws Exception{
        try(TlsFixture fixture=new TlsFixture(getClass().getResourceAsStream("/dns-test.p12"),false);DnsUpstream up=new DnsUpstream(PASS,new DnsProfiles.Selection(fixture.profile("localhost"),DnsProfiles.Transport.DOT),new String[]{"127.0.0.1"},fixture.server.getLocalPort(),fixture.client(),fixture.context.getSocketFactory())){
            for(int i=0;i<8;i++){byte[] q=DnsPackets.probe(100+i);assertTrue(DnsPackets.matches(q,up.resolve(q)));}
            assertEquals(1,up.tlsCreated.get());assertEquals(7,up.tlsReused.get());up.networkChanged();up.resolve(DnsPackets.probe(109));assertEquals(2,up.tlsCreated.get());up.close();assertTrue(up.sockets.isEmpty());
        }
    }
    @Test public void modernSsAndLegacyShareLinksParse()throws Exception{
        String credentials=Base64.getEncoder().encodeToString("aes-128-gcm:p+a:ss".getBytes(java.nio.charset.StandardCharsets.UTF_8));JSONObject parsed=ProxyConfig.parse("ss://"+credentials+"@127.0.0.1:8388#test");assertEquals("p+a:ss",parsed.getString("password"));assertEquals("shadowsocks",parsed.getString("type"));
        String entire=Base64.getEncoder().encodeToString("aes-256-gcm:secret@127.0.0.1:8388".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals("secret",ProxyConfig.parse("ss://"+entire).getString("password"));
        JSONObject root=ProxyConfig.client(parsed,1234,"u","p");assertEquals("proxy",root.getJSONObject("route").getString("final"));assertEquals("https",root.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("type"));
    }
    @Test public void vlessRealityAndTrojanTlsParse()throws Exception{
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);JSONObject vless=ProxyConfig.parse("vless://"+UUID.randomUUID()+"@example.com:443?security=reality&sni=example.com&pbk="+key+"&sid=abcd&fp=chrome&flow=xtls-rprx-vision");assertTrue(vless.getJSONObject("tls").getJSONObject("reality").getBoolean("enabled"));
        assertEquals("trojan",ProxyConfig.parse("trojan://pass%2Bword@example.com:443?sni=example.com").getString("type"));
    }
    @Test public void insecureOrRootConfigurationsAreRejected()throws Exception{
        String[] bad={"ss://bm9uZTpwYXNz@example.com:443","vless://"+UUID.randomUUID()+"@example.com:443?security=none","trojan://pass@example.com:443?allowInsecure=1","{\"type\":\"direct\"}","{\"outbounds\":[]}","{\"type\":\"trojan\",\"server\":\"example.com\",\"server_port\":443,\"password\":\"p\",\"tls\":{\"enabled\":true,\"insecure\":true}}"};
        for(String text:bad)try{ProxyConfig.parse(text);fail("Accepted insecure config");}catch(Exception expected){}
    }
    @Test public void logCannotAcceptConfigurationSecrets(){EventLog.record("ss://top-secret@example.com","password-SECRET",EventLog.Result.IO,5);String text=EventLog.text();assertFalse(text.contains("top-secret"));assertFalse(text.contains("password-SECRET"));assertTrue(text.contains("custom"));for(int i=0;i<180;i++)EventLog.record("vpn","VPN",EventLog.Result.OK,i);assertTrue(EventLog.text().split("\n").length<=160);}
    @Test public void dnsAddressParserRejectsTruncatedRecords()throws Exception{byte[] q=DnsPackets.probe(1),r=TlsFixture.response(q);assertEquals("203.0.113.8",DnsPackets.addresses(r).get(0).getHostAddress());try{DnsPackets.addresses(Arrays.copyOf(r,r.length-1));fail();}catch(IOException expected){}}
}
