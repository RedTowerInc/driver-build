package dev.geohide.switcher;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.system.OsConstants;
import android.system.Os;
import android.system.ErrnoException;
import android.system.StructPollfd;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DnsVpnService extends VpnService {
    enum State { OFF, STARTING, ON, WAITING, ERROR }
    static final class Snapshot {
        final State state;final String detail,server;final long answered,failed;
        Snapshot(State s,String d,String ip,long a,long f){state=s;detail=d;server=ip;answered=a;failed=f;}
    }
    private static volatile Snapshot snapshot=new Snapshot(State.OFF,"Нажмите, чтобы включить DNS","",0,0);
    static Snapshot current(){return snapshot;}
    static void clearError(){if(snapshot.state==State.ERROR)snapshot=new Snapshot(State.OFF,"DNS отключён","",0,0);}
    // Package-private injection, only used by instrumentation in the same process.
    static volatile String[] testServers;
    static volatile int testPort=53;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Session session;
    static DnsVpnService instance;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final int NOTE=1203;
    private long answered,failed;
    private static final class Session {
        final DnsUpstream upstream;
        final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());
        final ScheduledExecutorService health=Executors.newSingleThreadScheduledExecutor();
        final Object writeLock=new Object();
        final AtomicInteger id=new AtomicInteger(1);
        volatile boolean cancelled;
        ParcelFileDescriptor tun;
        FileInputStream input;
        FileOutputStream output;
        Thread reader;
        Session(DnsUpstream u){upstream=u;}
        void close(){
            cancelled=true;upstream.close();health.shutdownNow();workers.shutdownNow();
            if(reader!=null)reader.interrupt();
            // The descriptor has exactly one owner. Reader/writers never close wrappers.
            synchronized(writeLock){if(tun!=null)try{tun.close();}catch(IOException ignored){}tun=null;}
        }
    }
    @Override public void onCreate(){super.onCreate();instance=this;cm=getSystemService(ConnectivityManager.class);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null || "STOP".equals(intent.getAction())){disconnect(State.OFF,"DNS отключён");return START_NOT_STICKY;}
        if(session!=null)return START_NOT_STICKY;
        answered=failed=0;
        try{
            notification("Подключение…");
            if(VpnService.prepare(this)!=null)throw new IOException("Разрешение VPN не предоставлено");
            String[] servers=BuildConfig.DEBUG && testServers!=null?testServers:DnsUpstream.SERVERS;
            int port=BuildConfig.DEBUG && testServers!=null?testPort:53;
            Session s=new Session(new DnsUpstream(new DnsUpstream.Protector(){
                public boolean udp(java.net.DatagramSocket x){return protect(x);}
                public boolean tcp(java.net.Socket x){return protect(x);}
            },servers,port));
            session=s;publish(State.STARTING,"Проверяем ответ GeoHide…");
            s.workers.execute(()->{
                try{
                    byte[] r=s.upstream.resolve(DnsPackets.probe(0x6e01));
                    if((r[3]&15)!=0 || DnsPackets.u16(r,6)==0)throw new IOException("Проверочный домен GeoHide не разрешается");
                    main.post(()->establish(s));
                }catch(Exception e){main.post(()->{if(session==s)disconnect(State.ERROR,message(e));});}
            });
        }catch(Exception e){disconnect(State.ERROR,message(e));}
        return START_NOT_STICKY;
    }
    private void establish(Session s){
        if(session!=s || s.cancelled)return;
        try{
            s.tun=new Builder().setSession("GeoHide DNS").setMtu(1500).setBlocking(false)
                .addAddress("10.77.77.2",32).addDnsServer("10.77.77.1").addRoute("10.77.77.1",32)
                .allowFamily(OsConstants.AF_INET6).establish();
            if(s.tun==null)throw new IOException("Android отклонил подключение VPN");
            s.input=new FileInputStream(s.tun.getFileDescriptor());s.output=new FileOutputStream(s.tun.getFileDescriptor());
            s.reader=new Thread(()->read(s),"GeoHide-TUN");s.reader.start();
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){main.post(()->checkNetwork(s));}
                @Override public void onLost(Network n){main.post(()->checkNetwork(s));}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){main.post(()->checkNetwork(s));}
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);
            publish(State.ON,"GeoHide отвечает · DNS включён");
            s.health.scheduleWithFixedDelay(()->probe(s),15,15,TimeUnit.SECONDS);
        }catch(Exception e){disconnect(State.ERROR,message(e));}
    }
    private void checkNetwork(Session s){
        if(session!=s || s.cancelled)return;
        // A new socket chooses the current underlying network; no old persistent sockets.
        try{s.health.execute(()->probe(s));}catch(RejectedExecutionException ignored){}
    }
    private void probe(Session s){
        try{byte[] r=s.upstream.resolve(DnsPackets.probe(0x6e02));
            if((r[3]&15)!=0 || DnsPackets.u16(r,6)==0)throw new IOException("Нет ответа на проверочный домен");
            main.post(()->{if(session==s)publish(State.ON,"GeoHide отвечает · DNS включён");});
        }catch(IOException e){main.post(()->{if(session==s)publish(State.WAITING,"Нет ответа DNS. Можно отключить или дождаться сети.");});}
    }
    private void read(Session s){
        byte[] b=new byte[65535];
        try{
            while(!s.cancelled){
                StructPollfd poll=new StructPollfd();poll.fd=s.tun.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;
                if(Os.poll(new StructPollfd[]{poll},500)==0)continue;
                if(s.cancelled)break;
                int n;
                try{n=Os.read(s.tun.getFileDescriptor(),b,0,b.length);}
                catch(ErrnoException e){if(e.errno==OsConstants.EAGAIN || e.errno==OsConstants.EINTR){continue;}throw e;}
                if(n<=0)continue;
                DnsPackets.Query q=DnsPackets.parse(b,n);if(q==null)continue;
                try{s.workers.execute(()->forward(s,q));}
                catch(RejectedExecutionException e){write(s,q,DnsPackets.failure(q.dns));}
            }
        }catch(Exception e){main.post(()->{if(session==s && !s.cancelled)disconnect(State.ERROR,"Ошибка DNS-туннеля: "+message(e));});}
    }
    private void forward(Session s,DnsPackets.Query q){
        byte[] r;
        try{r=s.upstream.resolve(q.dns);
            main.post(()->{if(session==s){answered++;publish(State.ON,"GeoHide отвечает · DNS включён");}});
        }catch(IOException e){r=DnsPackets.failure(q.dns);
            main.post(()->{if(session==s){failed++;publish(State.WAITING,"Нет ответа DNS. Можно отключить или дождаться сети.");}});}
        write(s,q,r);
    }
    private void write(Session s,DnsPackets.Query q,byte[] dns){
        if(dns==null || s.cancelled)return;
        try{synchronized(s.writeLock){if(s.cancelled || s.tun==null)return;
            for(byte[] p:DnsPackets.replies(q,dns,s.id.incrementAndGet()))s.output.write(p);}}
        catch(IOException e){main.post(()->{if(session==s)disconnect(State.ERROR,"Не удалось отправить DNS-ответ");});}
    }
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void publish(State state,String detail){
        State previous=snapshot.state;
        snapshot=new Snapshot(state,detail,session==null?"":session.upstream.server,answered,failed);
        if(session!=null && previous!=state)notification(state==State.ON?"DNS включён":detail);
    }
    private void notification(String text){
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("geo","GeoHide DNS",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DnsVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"geo").setSmallIcon(R.drawable.ic_power).setContentTitle("GeoHide Switch")
            .setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(R.drawable.ic_power,"Отключить",stop).build()).build();
        startForeground(NOTE,n);
    }
    private void cleanup(){
        Session old=session;session=null;if(old!=null)old.close();
        if(networkCallback!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    private void disconnect(State state,String detail){cleanup();publish(state,detail);stopSelf();}
    @Override public void onDestroy(){instance=null;cleanup();if(snapshot.state!=State.ERROR)publish(State.OFF,"DNS отключён");super.onDestroy();}
    @Override public void onRevoke(){main.post(()->{if(instance==this)disconnect(State.OFF,"VPN отключён системой или другим приложением");});}
}
