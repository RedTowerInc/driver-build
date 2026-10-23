package dev.geohide.switcher;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class DnsUpstreamTest {
    private static final DnsUpstream.Protector PASS=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return true;}public boolean tcp(Socket s){return true;}};
    @Test(timeout=16000) public void nextRequestContinuesPastNodesThatConsumedTheBudget()throws Exception{
        List<DatagramSocket> sockets=new ArrayList<>();ExecutorService responder=Executors.newSingleThreadExecutor();
        try{
            DatagramSocket first=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));sockets.add(first);int port=first.getLocalPort();String[] nodes=new String[12];nodes[0]="127.0.0.1";
            for(int i=1;i<nodes.length;i++){nodes[i]="127.0.0."+(i+1);sockets.add(new DatagramSocket(port,InetAddress.getByName(nodes[i])));}
            DatagramSocket working=sockets.get(sockets.size()-1);
            Future<?> reply=responder.submit(()->{try{byte[] b=new byte[512];DatagramPacket p=new DatagramPacket(b,b.length);working.receive(p);byte[] q=Arrays.copyOf(b,p.getLength()),r=DnsPackets.failure(q);r[3]=(byte)0x80;working.send(new DatagramPacket(r,r.length,p.getSocketAddress()));}catch(IOException e){throw new RuntimeException(e);}});
            try(DnsUpstream up=new DnsUpstream(PASS,nodes,port)){
                try{up.resolve(DnsPackets.probe(40));fail("Silent nodes should exhaust first request");}catch(IOException expected){}
                long began=System.nanoTime();byte[] q=DnsPackets.probe(41);assertTrue(DnsPackets.matches(q,up.resolve(q)));
                assertTrue("Dead first nodes were retried instead of advancing",System.nanoTime()-began<4_000_000_000L);reply.get(1,TimeUnit.SECONDS);
            }
        }finally{for(DatagramSocket s:sockets)s.close();responder.shutdownNow();}
    }
    @Test public void responseAndCancellationCloseSockets()throws Exception{
        try(DatagramSocket server=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))){
            DnsUpstream up=new DnsUpstream(PASS,new String[]{"127.0.0.1"},server.getLocalPort());
            ExecutorService pool=Executors.newSingleThreadExecutor();
            Future<byte[]> f=pool.submit(()->up.resolve(DnsPackets.probe(4)));
            byte[] b=new byte[512];DatagramPacket p=new DatagramPacket(b,b.length);server.receive(p);
            byte[] r=DnsPackets.failure(Arrays.copyOf(b,p.getLength()));r[3]=(byte)0x80;
            server.send(new DatagramPacket(r,r.length,p.getSocketAddress()));assertTrue(DnsPackets.matches(DnsPackets.probe(4),f.get(2,TimeUnit.SECONDS)));
            Future<byte[]> blocked=pool.submit(()->up.resolve(DnsPackets.probe(5)));server.receive(p);
            long start=System.nanoTime();up.close();
            try{blocked.get(400,TimeUnit.MILLISECONDS);fail();}catch(ExecutionException expected){}
            assertTrue(System.nanoTime()-start<400_000_000L);assertTrue(up.sockets.isEmpty());pool.shutdownNow();
        }
    }
    @Test public void truncatedUdpRetriesOverTcp()throws Exception{
        try(ServerSocket tcp=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));DatagramSocket udp=new DatagramSocket(tcp.getLocalPort(),InetAddress.getByName("127.0.0.1"))){
            ExecutorService pool=Executors.newSingleThreadExecutor();DnsUpstream up=new DnsUpstream(PASS,new String[]{"127.0.0.1"},tcp.getLocalPort());
            Future<byte[]> f=pool.submit(()->up.resolve(DnsPackets.probe(8)));byte[] b=new byte[512];DatagramPacket p=new DatagramPacket(b,b.length);udp.receive(p);
            byte[] q=Arrays.copyOf(b,p.getLength()),r=DnsPackets.failure(q);r[2]|=2;r[3]=(byte)0x80;udp.send(new DatagramPacket(r,r.length,p.getSocketAddress()));
            try(Socket s=tcp.accept()){DataInputStream in=new DataInputStream(s.getInputStream());int n=in.readUnsignedShort();byte[] got=new byte[n];in.readFully(got);assertArrayEquals(q,got);
                r[2]&=~2;DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeShort(r.length);out.write(r);out.flush();}
            assertTrue(DnsPackets.matches(q,f.get(2,TimeUnit.SECONDS)));up.close();pool.shutdownNow();
        }
    }
}
