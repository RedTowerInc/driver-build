package dev.geohide.switcher;
import java.net.*;
public final class PublicDnsSmoke {
    public static void main(String[] args){
        DnsUpstream.Protector pass=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return true;}public boolean tcp(Socket s){return true;}};
        System.out.println("Public DNS availability from CI only. This is not a phone-network test.");
        for(DnsProfiles.Profile p:DnsProfiles.ALL){long start=System.nanoTime();
            try(DnsUpstream u=new DnsUpstream(pass,new DnsProfiles.Selection(p,DnsProfiles.Transport.DOH))){
                byte[] q=DnsPackets.probe(170);byte[] r=u.resolve(q);System.out.println(p.name+" HTTPS: OK; rcode="+(r[3]&15)+"; answers="+DnsPackets.u16(r,6)+"; ms="+(System.nanoTime()-start)/1000000);
            }catch(Exception e){System.out.println(p.name+" HTTPS: UNAVAILABLE from CI; "+e.getClass().getSimpleName());}
        }
    }
}
