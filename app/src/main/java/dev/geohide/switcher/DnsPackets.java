package dev.geohide.switcher;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** IPv4/UDP DNS packet framing; no Android dependencies. */
final class DnsPackets {
    static int u16(byte[] b,int x){return ((b[x]&255)<<8)|(b[x+1]&255);}
    static void p16(byte[] b,int x,int n){b[x]=(byte)(n>>>8);b[x+1]=(byte)n;}
    static long sum(byte[] b,int off,int len,long s){
        for(int i=0;i<len;i+=2)s+=((b[off+i]&255)<<8)|((i+1<len)?(b[off+i+1]&255):0);
        return s;
    }
    static int checksum(long s){while((s>>>16)!=0)s=(s&65535)+(s>>>16);return (int)(~s)&65535;}
    static final class Query {
        final byte[] header, udp, dns;
        Query(byte[] h,byte[] u,byte[] d){header=h;udp=u;dns=d;}
    }
    static Query parse(byte[] b,int n){
        if(n<40 || n>b.length || ((b[0]&255)>>>4)!=4)return null;
        int ihl=(b[0]&15)*4;
        if(ihl<20 || n<ihl+20 || (b[9]&255)!=17)return null;
        int total=u16(b,2), len=u16(b,ihl+4);
        if(total>n || total<ihl+20 || len<20 || ihl+len!=total || u16(b,ihl+2)!=53)return null;
        if((u16(b,6)&0x3fff)!=0 || checksum(sum(b,0,ihl,0))!=0)return null;
        if(b[16]!=10 || (b[17]&255)!=77 || (b[18]&255)!=77 || b[19]!=1)return null;
        byte[] q=Arrays.copyOfRange(b,ihl+8,total);
        if((q[2]&128)!=0 || u16(q,4)!=1 || questionEnd(q)<0)return null;
        return new Query(Arrays.copyOf(b,ihl),Arrays.copyOfRange(b,ihl,ihl+8),q);
    }
    static int questionEnd(byte[] q){
        if(q.length<17)return -1;
        int i=12;
        while(i<q.length){int len=q[i++]&255;if(len==0)return i+4<=q.length?i+4:-1;
            if(len>63 || i+len>q.length)return -1;i+=len;}
        return -1;
    }
    static boolean matches(byte[] q,byte[] r){
        int end=questionEnd(q);
        return end>0 && r.length>=end && r[0]==q[0] && r[1]==q[1]
            && (r[2]&128)!=0 && u16(r,4)==1
            && Arrays.equals(Arrays.copyOfRange(q,12,end),Arrays.copyOfRange(r,12,end));
    }
    static byte[] failure(byte[] q){
        int end=questionEnd(q);if(end<0)return null;
        byte[] r=Arrays.copyOf(q,end);r[2]=(byte)(0x80|(q[2]&0x79));r[3]=(byte)0x82;
        Arrays.fill(r,6,12,(byte)0);return r;
    }
    static byte[] probe(int id){return probe(id,"example.com");}
    static byte[] probe(int id,String domain){
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[] h=new byte[12];p16(h,0,id);h[2]=1;h[5]=1;b.write(h,0,h.length);
        for(String s:domain.split("\\.")){b.write(s.length());for(char c:s.toCharArray())b.write(c);}
        b.write(0);b.write(0);b.write(1);b.write(0);b.write(1);return b.toByteArray();
    }
    static byte[][] replies(Query q,byte[] dns,int identity){
        if(dns.length>65507)throw new IllegalArgumentException("DNS response too large");
        int udpLen=8+dns.length;byte[] udp=new byte[udpLen];
        p16(udp,0,u16(q.udp,2));p16(udp,2,u16(q.udp,0));p16(udp,4,udpLen);
        System.arraycopy(dns,0,udp,8,dns.length);
        long s=sum(q.header,12,8,17+udpLen);int cs=checksum(sum(udp,0,udp.length,s));p16(udp,6,cs==0?65535:cs);
        byte[][] out=new byte[(udpLen+1479)/1480][];
        for(int ofs=0,k=0;ofs<udpLen;k++){
            int chunk=Math.min(1480,udpLen-ofs);byte[] p=new byte[20+chunk];p[0]=0x45;p[8]=64;p[9]=17;
            p16(p,2,p.length);p16(p,4,identity);p16(p,6,ofs/8|(ofs+chunk<udpLen?0x2000:0));
            System.arraycopy(q.header,16,p,12,4);System.arraycopy(q.header,12,p,16,4);
            p16(p,10,checksum(sum(p,0,20,0)));System.arraycopy(udp,ofs,p,20,chunk);out[k]=p;ofs+=chunk;
        }
        return out;
    }
}
