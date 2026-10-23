package dev.geohide.switcher;
import android.content.Context;
import okhttp3.*;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
final class Diagnostics implements AutoCloseable {
    static final String[] NAMES={"ChatGPT","Gemini","YouTube","Telegram","Общий интернет"};
    private static final String[] URLS={"https://chatgpt.com/","https://gemini.google.com/","https://www.youtube.com/generate_204","https://web.telegram.org/","https://www.gstatic.com/generate_204"};
    static volatile String last="Проверка сервисов ещё не запускалась.";
    private final Context context;private volatile boolean cancelled;private DnsUpstream dns;private volatile Call call;private OkHttpClient ownClient;
    private final Set<Closeable> sockets=java.util.concurrent.ConcurrentHashMap.newKeySet();
    Diagnostics(Context context){this.context=context.getApplicationContext();}
    private synchronized void track(Closeable c)throws IOException{if(cancelled){c.close();throw new IOException("Cancelled");}sockets.add(c);}
    String run(int index){
        StringBuilder result=new StringBuilder(NAMES[index]+"\n");long began=System.nanoTime();DnsProfiles.Selection choice=DnsSettings.load(context);String mode=choice.transport.name();
        try{
            ProxyRuntime proxy=DnsVpnService.proxy();
            if(DnsSettings.fullVpn(context)){
                if(proxy==null || !proxy.alive())throw new IOException("VPN не подключён");
                result.append("Путь: через активное VPN-ядро\nDNS и TCP назначения выполняет прокси.\n");
                call=proxy.http().newCall(new Request.Builder().url(URLS[index]).build());
            }else{
                result.append("Путь: выбранный DNS + исходная сеть\n");
                DnsUpstream created=new DnsUpstream(NetworkProfiles.protector(context),choice);created.prefer(DnsSettings.preferred(context,NetworkProfiles.kind(context),choice.profile));synchronized(this){if(cancelled){created.close();throw new IOException("Cancelled");}dns=created;}
                String host=URI.create(URLS[index]).getHost();long stage=System.nanoTime();byte[] response=dns.resolve(DnsPackets.probe(0x7120+index,host));List<InetAddress> addresses=DnsPackets.addresses(response);
                if(addresses.isEmpty())throw new UnknownHostException();result.append("DNS: OK · ").append(ms(stage)).append(" мс · ").append(dns.actualTransport().label).append('\n');
                mode=dns.actualTransport().name();DnsSettings.remember(context,NetworkProfiles.kind(context),choice.profile,dns.actualTransport());
                Socket raw=new Socket();track(raw);if(!NetworkProfiles.protector(context).tcp(raw))throw new IOException("Физическая сеть недоступна");stage=System.nanoTime();raw.connect(new InetSocketAddress(addresses.get(0),443),2500);raw.setSoTimeout(2500);result.append("TCP: OK · ").append(ms(stage)).append(" мс\n");
                SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,host,443,true);track(tls);SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));tls.setSSLParameters(parameters);stage=System.nanoTime();tls.startHandshake();result.append("TLS: OK · ").append(ms(stage)).append(" мс · ").append(tls.getSession().getProtocol()).append('\n');tls.close();raw.close();sockets.remove(tls);sockets.remove(raw);
                ownClient=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(name->{if(!host.equalsIgnoreCase(name))throw new UnknownHostException();return addresses;}).socketFactory(new SocketFactory(){
                    public Socket createSocket()throws IOException{Socket s=new Socket();track(s);s.bind(new InetSocketAddress(0));if(!NetworkProfiles.protector(context).tcp(s)){s.close();throw new IOException("Физическая сеть недоступна");}return s;}
                    private Socket connected(InetAddress h,int p)throws IOException{Socket s=createSocket();s.connect(new InetSocketAddress(h,p),2500);return s;}
                    public Socket createSocket(String h,int p)throws IOException{return connected(InetAddress.getByName(h),p);}public Socket createSocket(InetAddress h,int p)throws IOException{return connected(h,p);}
                    public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return connected(InetAddress.getByName(h),p);}public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return connected(h,p);}
                }).followRedirects(false).connectTimeout(2500,TimeUnit.MILLISECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(7,TimeUnit.SECONDS).build();
                call=ownClient.newCall(new Request.Builder().url(URLS[index]).build());
            }
            if(cancelled)throw new IOException("Cancelled");long stage=System.nanoTime();
            try(Response response=call.execute()){
                int code=response.code();result.append("HTTPS: HTTP ").append(code).append(" · ").append(ms(stage)).append(" мс\n");
                if(code==429)result.append("Сервер ограничил частоту запросов. Это не доказательство блокировки сети.\n");
                else if(code==403)result.append("Сервер ответил отказом. Причина может быть в регионе, защите сайта или аккаунте.\n");
                else if(code>=300 && code<400)result.append("Получено перенаправление; вход в сервис не проверялся.\n");
                else result.append("Ответ сервера получен; вход в аккаунт и все функции не проверялись.\n");
                EventLog.record(DnsSettings.fullVpn(context)?"vpn":choice.profile.id,DnsSettings.fullVpn(context)?"VPN":mode,code<400?EventLog.Result.OK:EventLog.Result.HTTP_REFUSED,ms(began));
            }
        }catch(Exception e){EventLog.Result category=cancelled?EventLog.Result.CANCELLED:EventLog.category(e);result.append("Проверка остановилась: ").append(EventLog.label(category)).append("\nТочную причину блокировки по одному тесту определить нельзя.\n");EventLog.record(choice.profile.id,mode,category,ms(began));}
        finally{close();}
        String text=result.toString();if(!cancelled)last=text;return text;
    }
    private static long ms(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    static String report(Context c){DnsVpnService.Snapshot s=DnsVpnService.current();DnsProfiles.Selection choice=DnsSettings.load(c);return "GeoHide Switch "+BuildConfig.VERSION_NAME+"\nAndroid API "+android.os.Build.VERSION.SDK_INT+"\nСеть: "+NetworkProfiles.label(NetworkProfiles.kind(c))+"\nСистемный Private DNS задан: "+(NetworkProfiles.strictPrivateDns(c)?"да":"нет")+"\nРежим: "+(DnsSettings.fullVpn(c)?"VPN":"DNS")+"\nDNS-профиль: "+(DnsSettings.fullVpn(c)?"Cloudflare через VPN":choice.profile.name)+"\nВыбранный транспорт: "+(DnsSettings.fullVpn(c)?"HTTPS внутри прокси-туннеля":choice.transport.label)+"\nКаталог: DNS-AI от "+DnsCatalog.date(c)+"\nУзлов выбранного DNS: "+choice.profile.addresses.length+"\nСостояние: "+s.state+"\nОтветов DNS: "+s.answered+"; ошибок: "+s.failed+"\n\nПоследняя проверка:\n"+last+"\n\nОграниченный журнал:\n"+EventLog.text()+"\n\nВ отчёте нет ключей VPN, конфигураций, cookies, заголовков авторизации или истории посещений. Проверочные запросы отправлялись только к выбранному контрольному сервису.\n";}
    @Override public synchronized void close(){
        if(dns!=null)dns.close();Call pending=call;OkHttpClient client=ownClient;Closeable[] captured=sockets.toArray(new Closeable[0]);sockets.clear();for(Closeable socket:captured)if(!(socket instanceof SSLSocket))IoCleanup.close(socket);
        IoCleanup.run(()->{if(pending!=null)pending.cancel();for(Closeable socket:captured)if(socket instanceof SSLSocket)IoCleanup.close(socket);if(client!=null){client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}});
    }
    void cancel(){cancelled=true;close();}
}
