package dev.geohide.switcher;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.system.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class DnsVpnService extends VpnService {
    enum State { OFF, STARTING, ON, WAITING, ERROR }
    static final class Snapshot {
        final State state;final String detail,server;final long answered,failed;final DnsProfiles.Selection choice;final boolean full;
        Snapshot(State s,String d,String ip,long a,long f){this(s,d,ip,a,f,null,false);}
        Snapshot(State s,String d,String ip,long a,long f,DnsProfiles.Selection choice,boolean full){state=s;detail=d;server=ip;answered=a;failed=f;this.choice=choice;this.full=full;}
    }
    private static volatile Snapshot snapshot=new Snapshot(State.OFF,"Нажмите, чтобы подключиться","",0,0);
    static Snapshot current(){return snapshot;}
    static void clearError(){if(snapshot.state==State.ERROR)snapshot=new Snapshot(State.OFF,"Отключено","",0,0);}
    static volatile String[] testServers;static volatile int testPort=53;static volatile DnsUpstream testUpstream;
    private final Handler main=new Handler(Looper.getMainLooper());private volatile Session session;
    static volatile DnsVpnService instance;
    private ConnectivityManager cm;private ConnectivityManager.NetworkCallback networkCallback;private static final int NOTE=1203;
    private long answered,failed;
    static ProxyRuntime proxy(){DnsVpnService service=instance;Session s=service==null?null:service.session;return s==null?null:s.proxy;}
    private static final class Session {
        volatile DnsUpstream upstream;volatile DnsProfiles.Selection choice;final ProxyRuntime proxy;
        volatile String kind,key;Runnable networkTask;
        final ExecutorService workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.AbortPolicy());
        final ScheduledExecutorService health=Executors.newSingleThreadScheduledExecutor();final Object writeLock=new Object();final AtomicInteger id=new AtomicInteger(1);
        volatile boolean cancelled;ParcelFileDescriptor tun;Thread reader;
        Session(DnsUpstream u,DnsProfiles.Selection choice,ProxyRuntime proxy,String kind,String key){upstream=u;this.choice=choice;this.proxy=proxy;this.kind=kind;this.key=key;}
        void close(){cancelled=true;if(upstream!=null)upstream.close();health.shutdownNow();workers.shutdownNow();if(proxy!=null)proxy.close();if(reader!=null)reader.interrupt();synchronized(writeLock){if(tun!=null)try{tun.close();}catch(IOException ignored){}tun=null;}}
    }
    @Override public void onCreate(){super.onCreate();instance=this;cm=getSystemService(ConnectivityManager.class);}
    private String key(){Network n=NetworkProfiles.physical(this);return (n==null?"none":n.toString())+"|"+NetworkProfiles.kind(this);}
    private DnsUpstream upstream(DnsProfiles.Selection choice,String kind){
        DnsUpstream.Protector protector=NetworkProfiles.protector(this);
        DnsUpstream u=BuildConfig.DEBUG && testUpstream!=null?testUpstream:BuildConfig.DEBUG && testServers!=null?new DnsUpstream(protector,testServers,testPort):new DnsUpstream(protector,choice);
        u.prefer(DnsSettings.preferred(this,kind,choice.profile));return u;
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null || "STOP".equals(intent.getAction())){disconnect(State.OFF,"Отключено");return START_NOT_STICKY;}
        if(session!=null)return START_NOT_STICKY;answered=failed=0;
        try{
            notification("Подключение…");if(VpnService.prepare(this)!=null)throw new IOException("Разрешение VPN не предоставлено");
            String kind=NetworkProfiles.kind(this);DnsProfiles.Selection choice=DnsSettings.loadFor(this,kind);boolean full=DnsSettings.fullVpn(this);
            Session s=new Session(full?null:upstream(choice,kind),choice,full?new ProxyRuntime(this):null,kind,key());session=s;
            EventLog.record(full?"vpn":choice.profile.id,full?"VPN":choice.transport.name(),EventLog.Result.START,0);
            publish(State.STARTING,full?"Проверяем VPN-сервер и DNS через туннель…":"Проверяем "+choice.profile.name+"…");
            s.workers.execute(()->{long start=System.nanoTime();try{
                if(s.proxy!=null)s.proxy.start();else verify(s.upstream);
                main.post(()->{if(session!=s || s.cancelled)return;remember(s);if(s.proxy!=null)establishFull(s);else establish(s);});
            }catch(Exception e){EventLog.record(full?"vpn":choice.profile.id,full?"VPN":choice.transport.name(),EventLog.category(e),ms(start));main.post(()->{if(session==s)disconnect(State.ERROR,message(e));});}});
        }catch(Exception e){disconnect(State.ERROR,message(e));}return START_NOT_STICKY;
    }
    private static long ms(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    private static void verify(DnsUpstream u)throws IOException{byte[] r=u.resolve(DnsPackets.probe(0x6e01));if((r[3]&15)!=0 || DnsPackets.u16(r,6)==0)throw new IOException("Проверочный домен не разрешается");}
    private void remember(Session s){if(s.upstream!=null)DnsSettings.remember(this,s.kind,s.choice.profile,s.upstream.actualTransport());}
    private void establish(Session s){
        if(session!=s || s.cancelled)return;
        try{s.tun=new Builder().setSession(s.choice.label()).setMtu(1500).setBlocking(false).addAddress("10.77.77.2",32).addDnsServer("10.77.77.1").addRoute("10.77.77.1",32).allowFamily(OsConstants.AF_INET6).establish();
            if(s.tun==null)throw new IOException("Android отклонил подключение VPN");s.reader=new Thread(()->read(s),"GeoHide-TUN");s.reader.start();watchNetwork(s);publish(State.ON,s.choice.profile.name+" отвечает · DNS включён");s.health.scheduleWithFixedDelay(()->probe(s),15,15,TimeUnit.SECONDS);
        }catch(Exception e){disconnect(State.ERROR,message(e));}
    }
    private void establishFull(Session s){watchNetwork(s);publish(State.ON,"VPN подключён · сервер и DNS ответили");s.health.scheduleWithFixedDelay(()->{if(!s.proxy.alive())main.post(()->{if(session==s)disconnect(State.ERROR,"VPN-ядро завершилось");});},2,2,TimeUnit.SECONDS);}
    private void watchNetwork(Session s){
        networkCallback=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){scheduleNetwork(s);}public void onLost(Network n){scheduleNetwork(s);}public void onCapabilitiesChanged(Network n,NetworkCapabilities c){scheduleNetwork(s);}};
        cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);
    }
    private void scheduleNetwork(Session s){main.post(()->{if(session!=s || s.cancelled)return;if(s.networkTask!=null)main.removeCallbacks(s.networkTask);s.networkTask=()->checkNetwork(s);main.postDelayed(s.networkTask,400);});}
    void testNetworkChanged(){if(BuildConfig.DEBUG){Session s=session;if(s!=null)main.post(()->checkNetwork(s));}}
    private void checkNetwork(Session s){
        if(session!=s || s.cancelled)return;String key=key();if(key.equals(s.key))return;s.key=key;String kind=NetworkProfiles.kind(this);EventLog.record(s.proxy!=null?"vpn":s.choice.profile.id,s.proxy!=null?"VPN":s.choice.transport.name(),EventLog.Result.NETWORK,0);
        if(s.proxy!=null){s.kind=kind;publish(NetworkProfiles.physical(this)==null?State.WAITING:State.ON,"Сеть изменилась · проверьте доступность нужного сервиса");return;}
        if(!kind.equals(s.kind)){
            DnsProfiles.Selection choice=DnsSettings.loadFor(this,kind);DnsUpstream next=upstream(choice,kind),previous=s.upstream;s.choice=choice;s.kind=kind;s.upstream=next;previous.close();
        }else s.upstream.networkChanged();
        publish(State.WAITING,"Сеть изменилась · проверяем DNS…");try{s.health.execute(()->probe(s));}catch(RejectedExecutionException ignored){}
    }
    private void probe(Session s){DnsUpstream u=s.upstream;long start=System.nanoTime();try{verify(u);main.post(()->{if(session==s && s.upstream==u){remember(s);EventLog.record(s.choice.profile.id,u.actualTransport().name(),EventLog.Result.OK,ms(start));publish(State.ON,s.choice.profile.name+" отвечает · DNS включён");}});}catch(IOException e){main.post(()->{if(session==s && s.upstream==u){EventLog.record(s.choice.profile.id,s.choice.transport.name(),EventLog.category(e),ms(start));publish(State.WAITING,"Нет ответа DNS · можно отключить или дождаться сети");}});}}
    private void read(Session s){byte[] b=new byte[65535];try{while(!s.cancelled){StructPollfd poll=new StructPollfd();poll.fd=s.tun.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;if(Os.poll(new StructPollfd[]{poll},500)==0)continue;if(s.cancelled)break;int n;try{n=Os.read(s.tun.getFileDescriptor(),b,0,b.length);}catch(ErrnoException e){if(e.errno==OsConstants.EAGAIN || e.errno==OsConstants.EINTR)continue;throw e;}if(n<=0)continue;DnsPackets.Query q=DnsPackets.parse(b,n);if(q==null)continue;try{s.workers.execute(()->forward(s,q));}catch(RejectedExecutionException e){write(s,q,DnsPackets.failure(q.dns));}}}catch(Exception e){main.post(()->{if(session==s && !s.cancelled)disconnect(State.ERROR,"Ошибка DNS-туннеля");});}}
    private void forward(Session s,DnsPackets.Query q){byte[] r;DnsUpstream u=s.upstream;try{r=u.resolve(q.dns);main.post(()->{if(session==s && s.upstream==u){answered++;remember(s);publish(State.ON,s.choice.profile.name+" отвечает · DNS включён");}});}catch(IOException e){r=DnsPackets.failure(q.dns);main.post(()->{if(session==s && s.upstream==u){failed++;EventLog.record(s.choice.profile.id,s.choice.transport.name(),EventLog.category(e),0);publish(State.WAITING,"Нет ответа DNS · можно отключить или дождаться сети");}});}write(s,q,r);}
    private void write(Session s,DnsPackets.Query q,byte[] dns){if(dns==null || s.cancelled)return;try{synchronized(s.writeLock){if(s.cancelled || s.tun==null)return;for(byte[] p:DnsPackets.replies(q,dns,s.id.incrementAndGet()))Os.write(s.tun.getFileDescriptor(),p,0,p.length);}}catch(Exception e){main.post(()->{if(session==s)disconnect(State.ERROR,"Не удалось отправить DNS-ответ");});}}
    private static String message(Exception e){if(e instanceof IllegalStateException)return "Сначала импортируйте конфигурацию VPN-сервера";if(e instanceof javax.net.ssl.SSLException)return "Ошибка проверки TLS-сертификата";if(e instanceof java.net.SocketTimeoutException)return "Сетевой запрос превысил время ожидания";if(e instanceof IOException && e.getMessage()!=null && !e.getMessage().contains("://") && e.getMessage().length()<130)return e.getMessage();return "Подключение не удалось · "+EventLog.category(e);}
    private void publish(State state,String detail){State previous=snapshot.state;Session s=session;snapshot=new Snapshot(state,detail,s==null?"":s.proxy!=null?"VPN · "+s.proxy.type:s.upstream.server,answered,failed,s==null?null:s.choice,s!=null && s.proxy!=null);if(s!=null && previous!=state)notification(state==State.ON?"Подключено":detail);}
    private void notification(String text){NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("geo","GeoHide подключение",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DnsVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification n=new Notification.Builder(this,"geo").setSmallIcon(R.drawable.ic_power).setContentTitle("GeoHide Switch").setContentText(text).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(R.drawable.ic_power,"Отключить",stop).build()).build();startForeground(NOTE,n);}
    private void cleanup(){Session old=session;session=null;if(old!=null){if(old.networkTask!=null)main.removeCallbacks(old.networkTask);old.close();}if(networkCallback!=null){try{cm.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}networkCallback=null;}stopForeground(STOP_FOREGROUND_REMOVE);}
    private void disconnect(State state,String detail){Session old=session;if(old!=null)EventLog.record(old.proxy!=null?"vpn":old.choice.profile.id,old.proxy!=null?"VPN":old.choice.transport.name(),EventLog.Result.STOP,0);cleanup();publish(state,detail);stopSelf();}
    @Override public void onDestroy(){if(instance==this)instance=null;cleanup();if(snapshot.state!=State.ERROR)publish(State.OFF,"Отключено");super.onDestroy();}
    @Override public void onRevoke(){main.post(()->{if(instance==this)disconnect(State.OFF,"VPN отключён системой или другим приложением");});}
}
