package dev.geohide.switcher;
import android.app.*;
import android.content.Intent;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DnsVpnService extends VpnService {
    public static volatile boolean active=false;
    private static final String[] GEO={"193.233.112.68","193.233.112.88","159.194.200.33","45.155.204.190","37.230.192.51","46.8.158.6"};
    private ParcelFileDescriptor tun;
    private volatile boolean running=false;
    private ExecutorService workers;
    private FileOutputStream output;
    private final Object writeLock=new Object();
    private final AtomicInteger id=new AtomicInteger(1),preferred=new AtomicInteger(0);
    private static int u16(byte[] b,int x){return ((b[x]&255)<<8)|(b[x+1]&255);}
    private static void p16(byte[] b,int x,int n){b[x]=(byte)(n>>>8);b[x+1]=(byte)n;}
    private static long sum(byte[] b,int off,int len,long s){
        for(int i=0;i<len;i+=2) s+=((b[off+i]&255)<<8)|((i+1<len)?(b[off+i+1]&255):0);
        return s;
    }
    private static int checksum(long s){while((s>>>16)!=0)s=(s&65535)+(s>>>16);return (int)(~s)&65535;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(running)return START_STICKY;
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel chan=new NotificationChannel("geo","GeoHide DNS",NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(chan);
        PendingIntent click=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,"geo").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GeoHide DNS").setContentText("DNS-туннель включён").setOngoing(true).setContentIntent(click).build();
        startForeground(1203,note);
        try {
            tun=new Builder().setSession("GeoHide DNS").setMtu(1500)
                .addAddress("10.77.77.2",32).addDnsServer("10.77.77.1").addRoute("10.77.77.1",32).establish();
            if(tun==null)throw new IOException("VPN rejected");
            output=new FileOutputStream(tun.getFileDescriptor());
            running=true;active=true;
            workers=new ThreadPoolExecutor(3,6,20,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(100),
                new ThreadPoolExecutor.DiscardPolicy());
            new Thread(this::read,"GeoHide-TUN").start();
        }catch(Exception e){stopSelf();}
        return START_STICKY;
    }
    private void read() {
        try(FileInputStream in=new FileInputStream(tun.getFileDescriptor())){
            byte[] b=new byte[32768];
            while(running){
                int n=in.read(b);if(n<29)continue;
                int ihl=(b[0]&15)*4;
                if((b[0]>>>4)!=4 || ihl<20 || n<ihl+8 || (b[9]&255)!=17)continue;
                int total=u16(b,2), len=u16(b,ihl+4);
                if(total>n || total<ihl+8 || len<8 || ihl+len>total || u16(b,ihl+2)!=53)continue;
                if((u16(b,6)&0x3fff)!=0)continue;
                byte[] head=Arrays.copyOf(b,ihl);
                byte[] udp=Arrays.copyOfRange(b,ihl,ihl+8);
                byte[] q=Arrays.copyOfRange(b,ihl+8,ihl+len);
                if(q.length<12)continue;
                workers.execute(()->forward(head,udp,q));
            }
        }catch(Exception ignored){}finally{stopSelf();}
    }
    private void forward(byte[] head,byte[] udp,byte[] q) {
        byte[] response=null;
        int start=Math.floorMod(preferred.get(),GEO.length);
        for(int x=0;running && x<GEO.length;x++){
            int idx=(start+x)%GEO.length;
            try(DatagramSocket sock=new DatagramSocket()){
                if(!protect(sock))break;
                sock.setSoTimeout(x==0?1200:900);
                sock.connect(InetAddress.getByName(GEO[idx]),53);
                sock.send(new DatagramPacket(q,q.length));
                byte[] r=new byte[8192];
                DatagramPacket pkt=new DatagramPacket(r,r.length);
                sock.receive(pkt);
                if(pkt.getLength()>=12 && r[0]==q[0] && r[1]==q[1] && (r[2]&128)!=0){
                    response=Arrays.copyOf(r,pkt.getLength());preferred.set(idx);break;
                }
            }catch(Exception ignored){}
        }
        if(response==null || !running)return;
        try{reply(head,udp,response);}catch(Exception ignored){}
    }
    private void reply(byte[] request,byte[] reqUdp,byte[] dns)throws IOException{
        int udpLen=8+dns.length;
        byte[] full=new byte[udpLen];
        p16(full,0,u16(reqUdp,2));p16(full,2,u16(reqUdp,0));
        p16(full,4,udpLen);System.arraycopy(dns,0,full,8,dns.length);
        long s=0;
        for(int i=16;i<20;i+=2)s+=u16(request,i);
        for(int i=12;i<16;i+=2)s+=u16(request,i);
        s+=17+udpLen;
        int cs=checksum(sum(full,0,full.length,s));
        p16(full,6,cs==0?65535:cs);
        int identity=id.getAndIncrement()&65535;
        for(int ofs=0;ofs<full.length && running;){
            int chunk=Math.min(1480,full.length-ofs);
            if(ofs+chunk<full.length)chunk&=~7;
            byte[] packet=new byte[20+chunk];
            packet[0]=0x45;packet[8]=64;packet[9]=17;
            p16(packet,2,packet.length);p16(packet,4,identity);p16(packet,6,ofs/8);
            System.arraycopy(request,16,packet,12,4);
            System.arraycopy(request,12,packet,16,4);
            if(ofs+chunk<full.length)p16(packet,6,(ofs/8)|0x2000);
            p16(packet,10,checksum(sum(packet,0,20,0)));
            System.arraycopy(full,ofs,packet,20,chunk);
            synchronized(writeLock){if(running && output!=null)output.write(packet);}
            ofs+=chunk;
        }
    }
    @Override public void onDestroy(){
        running=false;active=false;
        if(tun!=null)try{tun.close();}catch(Exception ignored){}
        if(output!=null)try{output.close();}catch(Exception ignored){}
        if(workers!=null)workers.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public void onRevoke(){stopSelf();}
}