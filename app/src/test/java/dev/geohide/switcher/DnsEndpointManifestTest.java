package dev.geohide.switcher;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;

public class DnsEndpointManifestTest {
    private static String document(long serial,String v4,String v6){return "{\"v\":1,\"serial\":"+serial+",\"updated\":\"2026-10-18\",\"v4\":"+v4+",\"v6\":"+v6+"}";}
    @Test public void currentOfficialManifestKeepsFourNodes()throws Exception{
        DnsEndpointManifest m=DnsEndpointManifest.parse(document(7,"[\"194.87.196.13\",\"45.151.102.219\"]","[\"2a00:b700:5::3:62\",\"2a12:5940:d4ef::219\"]"),7);
        assertEquals(7,m.serial);assertEquals(4,m.addresses.length);assertEquals("2026-10-18",m.updated);
        assertFalse(Arrays.asList(m.addresses).contains("192.144.59.14"));
        assertEquals(4,DnsEndpointManifest.parse(m.json,7).addresses.length);
    }
    @Test public void staleEmptyAndUnsupportedManifestsAreRejected()throws Exception{
        String[] bad={document(6,"[\"1.1.1.1\"]","[]"),document(7,"[]","[]"),document(7,"[null]","[]"),"<html>Service Unavailable</html>",document(7,"[\"1.1.1.1\"]","[]").replace("\"v\":1","\"v\":2")};
        for(String text:bad)try{DnsEndpointManifest.parse(text,7);fail("Accepted invalid manifest");}catch(IOException expected){}
    }
    @Test public void hostnamesLocalAddressesAndInvalidFamiliesAreRejected()throws Exception{
        String[] bad={"127.0.0.1","10.0.0.1","169.254.1.1","0.0.0.0","255.255.255.255","224.0.0.1","::1","fe80::1","fd00::1","dns.example.com","1.1.1.1:53","999.1.1.1","01.1.1.1"};
        for(String ip:bad)try{DnsEndpointManifest.publicAddress(ip);fail("Accepted non-public literal: "+ip);}catch(IOException expected){}
        try{DnsEndpointManifest.parse(document(7,"[\"2001:4860:4860::8888\"]","[]"),7);fail();}catch(IOException expected){}
    }
    @Test public void duplicateNodesDoNotInflateTheList()throws Exception{
        DnsEndpointManifest m=DnsEndpointManifest.parse(document(7,"[\"1.1.1.1\",\"1.1.1.1\"]","[]"),7);assertEquals(1,m.addresses.length);
    }
    @Test public void addressUpdatePreservesProviderAndTransportPolicy()throws Exception{
        DnsProfiles.Profile original=DnsProfiles.find("dns_ai");String[] old=original.addresses.clone();
        try{DnsProfiles.addresses("dns_ai",new String[]{"1.1.1.1"});DnsProfiles.Profile next=DnsProfiles.find("dns_ai");
            assertEquals(original.dohUrl,next.dohUrl);assertEquals(original.tlsHost,next.tlsHost);assertEquals(DnsProfiles.Transport.DOT,next.initial);assertFalse(next.plainAllowed);
            assertFalse(next.supports(DnsProfiles.Transport.UDP));assertEquals("194.87.196.13",DnsProfiles.ALL[1].addresses[0]);
        }finally{DnsProfiles.addresses("dns_ai",old);}
    }
}
