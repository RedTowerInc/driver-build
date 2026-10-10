package dev.geohide.switcher;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded upstream work; stop closes every socket, including blocking receives. */
final class DnsUpstream implements Closeable {
    interface Protector { boolean udp(DatagramSocket s); boolean tcp(Socket s); }
    static final String[] SERVERS={"193.233.112.68","193.233.112.67","193.233.112.88","45.155.204.190","37.230.192.51","46.8.158.6","159.194.200.33"};
    final Set<Closeable> sockets=ConcurrentHashMap.newKeySet();
    private final Protector protector;
    private final String[] servers;
    private final int port;
    private final AtomicInteger preferred=new AtomicInteger();
    private volatile boolean closed;
    volatile String server="";
    DnsUpstream(Protector p,String[] servers,int port){this.protector=p;this.servers=servers.clone();this.port=port;}
    private synchronized void track(Closeable s)throws IOException{
        if(closed){s.close();throw new IOException("Cancelled");}sockets.add(s);
    }
    byte[] resolve(byte[] query)throws IOException{
        int first=Math.floorMod(preferred.get(),servers.length);
        long deadline=System.nanoTime()+5_500_000_000L;
        for(int n=0;n<servers.length && !closed && System.nanoTime()<deadline;n++){
            int i=(first+n)%servers.length;
            try {
                byte[] r=udp(query,servers[i]);
                if((r[2]&2)!=0)r=tcp(query,servers[i]);
                if((r[3]&15)==2 || (r[3]&15)==5)continue;
                preferred.set(i);server=servers[i];return r;
            }catch(IOException ignored){}
        }
        throw new IOException(closed?"Cancelled":"GeoHide DNS не отвечает");
    }
    private byte[] udp(byte[] q,String ip)throws IOException{
        DatagramSocket s=new DatagramSocket();
        try{track(s);if(!protector.udp(s))throw new IOException("VPN socket protection failed");
            s.setSoTimeout(700);s.connect(InetAddress.getByName(ip),port);s.send(new DatagramPacket(q,q.length));
            byte[] r=new byte[65507];DatagramPacket p=new DatagramPacket(r,r.length);s.receive(p);
            byte[] result=Arrays.copyOf(r,p.getLength());
            if(!DnsPackets.matches(q,result))throw new IOException("Invalid DNS response");return result;
        }finally{sockets.remove(s);s.close();}
    }
    private byte[] tcp(byte[] q,String ip)throws IOException{
        Socket s=new Socket();
        try{track(s);if(!protector.tcp(s))throw new IOException("VPN socket protection failed");
            s.connect(new InetSocketAddress(ip,port),700);s.setSoTimeout(700);
            DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeShort(q.length);out.write(q);out.flush();
            DataInputStream in=new DataInputStream(s.getInputStream());int len=in.readUnsignedShort();
            if(len<12 || len>65507)throw new IOException("Invalid DNS length");byte[] r=new byte[len];in.readFully(r);
            if(!DnsPackets.matches(q,r) || (r[2]&2)!=0)throw new IOException("Invalid TCP DNS response");return r;
        }finally{sockets.remove(s);s.close();}
    }
    @Override public synchronized void close(){closed=true;for(Closeable s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();}
}
