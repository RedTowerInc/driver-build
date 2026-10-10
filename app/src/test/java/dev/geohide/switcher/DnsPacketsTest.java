package dev.geohide.switcher;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class DnsPacketsTest {
    static DnsPackets.Query query(){
        byte[] h=new byte[20];h[0]=0x45;h[8]=64;h[9]=17;h[12]=10;h[13]=77;h[14]=77;h[15]=2;
        h[16]=10;h[17]=77;h[18]=77;h[19]=1;byte[] u=new byte[8];DnsPackets.p16(u,0,40001);DnsPackets.p16(u,2,53);
        return new DnsPackets.Query(h,u,DnsPackets.probe(1234));
    }
    @Test public void roundTripAndChecksums(){
        DnsPackets.Query q=query();byte[][] packets=DnsPackets.replies(q,q.dns,42);byte[] p=packets[0];
        assertEquals(0,DnsPackets.checksum(DnsPackets.sum(p,0,20,0)));
        assertEquals(0,DnsPackets.checksum(DnsPackets.sum(p,20,p.length-20,DnsPackets.sum(p,12,8,17+p.length-20))));
        // Convert reply packet into a request to the fake DNS address.
        for(int i=0;i<4;i++){byte temp=p[12+i];p[12+i]=p[16+i];p[16+i]=temp;}
        DnsPackets.p16(p,20,40001);DnsPackets.p16(p,22,53);DnsPackets.p16(p,26,0);DnsPackets.p16(p,10,0);
        DnsPackets.p16(p,10,DnsPackets.checksum(DnsPackets.sum(p,0,20,0)));
        DnsPackets.Query parsed=DnsPackets.parse(p,p.length);assertNotNull(parsed);assertArrayEquals(q.dns,parsed.dns);
        p[16]=8;assertNull(DnsPackets.parse(p,p.length));
    }
    @Test public void malformedPacketsNeverThrow(){
        Random random=new Random(31);
        for(int i=0;i<10000;i++){byte[] p=new byte[random.nextInt(200)];random.nextBytes(p);DnsPackets.parse(p,p.length);}
        assertNull(DnsPackets.parse(new byte[10],100));
    }
    @Test public void servfailMatchesOriginalQuestion(){
        byte[] q=DnsPackets.probe(7),r=DnsPackets.failure(q);assertTrue(DnsPackets.matches(q,r));assertEquals(2,r[3]&15);
        r[1]++;assertFalse(DnsPackets.matches(q,r));r[1]--;r[13]++;assertFalse(DnsPackets.matches(q,r));
    }
    @Test public void fragmentationPreservesAllPayload(){
        DnsPackets.Query q=query();byte[] dns=new byte[4097];new Random(7).nextBytes(dns);
        byte[][] p=DnsPackets.replies(q,dns,99);assertEquals(3,p.length);
        byte[] reassembled=new byte[4105];int off=0;
        for(int i=0;i<p.length;i++){
            assertTrue(p[i].length<=1500);assertEquals(0,DnsPackets.checksum(DnsPackets.sum(p[i],0,20,0)));
            assertEquals(off/8,DnsPackets.u16(p[i],6)&0x1fff);assertEquals(i<p.length-1,(DnsPackets.u16(p[i],6)&0x2000)!=0);
            System.arraycopy(p[i],20,reassembled,off,p[i].length-20);off+=p[i].length-20;
        }
        assertArrayEquals(dns,Arrays.copyOfRange(reassembled,8,reassembled.length));
    }
}
