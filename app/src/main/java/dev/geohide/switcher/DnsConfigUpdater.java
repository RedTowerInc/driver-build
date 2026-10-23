package dev.geohide.switcher;

import android.content.Context;
import android.net.Network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Explicit, cancellable refresh over authenticated HTTPS. Existing good data survives failure. */
final class DnsConfigUpdater implements AutoCloseable {
    static final String[] SOURCES={"https://raw.githubusercontent.com/confeden/DNS-AI/main/endpoints.json","https://dns-ai.ru/endpoints.json"};
    private final Context context;
    private volatile boolean closed;
    private volatile boolean updated;
    private volatile Call call;
    private OkHttpClient http;
    private final Set<DnsUpstream> probes=ConcurrentHashMap.newKeySet();
    private final ExecutorService pool=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"DNS-settings-check");t.setDaemon(true);return t;});
    DnsConfigUpdater(Context context){this.context=context.getApplicationContext();}
    boolean updated(){return updated;}
    String run(){
        StringBuilder result=new StringBuilder();
        try{
            Network physical=NetworkProfiles.physical(context);if(physical==null)throw new IOException("Нет физического подключения к сети");
            http=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(physical.getSocketFactory())
                .dns(host->Arrays.asList(physical.getAllByName(host))).followRedirects(false).followSslRedirects(false)
                .connectTimeout(3,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(7,TimeUnit.SECONDS).build();
            DnsEndpointManifest manifest=null;IOException last=null;
            for(String url:SOURCES){
                if(closed)throw new IOException("Отменено");
                try{manifest=DnsEndpointManifest.parse(document(url),DnsCatalog.serial(context));break;}catch(IOException e){last=e;}
            }
            if(manifest!=null){
                if(closed)throw new IOException("Отменено");DnsCatalog.save(context,manifest);updated=true;
                result.append("DNS-AI: список обновлён · ").append(manifest.updated).append("\nУзлов: ").append(manifest.addresses.length).append(". Выбранный транспорт сохранён.\n");
            }else result.append("DNS-AI: источник обновления недоступен · ").append(EventLog.label(EventLog.category(last))).append(".\nСохранён список от ").append(DnsCatalog.date(context)).append(".\n");
            if(!closed)result.append(geoHide(physical));
            result.append("\nНастройки обновляются при отключённом VPN. Для проверки соединения нажмите «Включить».");
        }catch(Exception e){result.append(closed?"Обновление отменено.":"Обновление не завершено: "+EventLog.label(EventLog.category(e))+". Сохранённые настройки доступны.");}
        finally{close();}
        return result.toString();
    }
    private String document(String url)throws IOException{
        if(closed)throw new IOException("Отменено");
        Call next=http.newCall(new Request.Builder().url(url).header("Accept","application/json").build());call=next;
        if(closed){next.cancel();throw new IOException("Отменено");}
        try(Response response=next.execute()){
            if(response.code()!=200 || response.body()==null)throw new IOException("HTTP "+response.code());
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
            try(InputStream in=response.body().byteStream()){while((count=in.read(buffer))!=-1){if(closed)throw new IOException("Отменено");if(out.size()+count>65536)throw new IOException("Слишком большой список DNS");out.write(buffer,0,count);}}
            return out.toString(StandardCharsets.UTF_8.name());
        }finally{call=null;}
    }
    private String geoHide(Network physical)throws Exception{
        LinkedHashSet<String> candidates=new LinkedHashSet<>();
        Future<InetAddress[]> lookup=pool.submit(()->physical.getAllByName("dns.geohide.ru"));
        try{for(InetAddress ip:lookup.get(2500,TimeUnit.MILLISECONDS))try{candidates.add(DnsEndpointManifest.publicAddress(ip.getHostAddress()).getHostAddress());}catch(IOException ignored){}}
        catch(Exception ignored){lookup.cancel(true);}
        candidates.addAll(Arrays.asList(DnsProfiles.find("geohide").addresses));
        CompletionService<Node> completed=new ExecutorCompletionService<>(pool);
        int count=0;
        for(String ip:candidates){
            if(count>=24 || closed)break;count++;
            completed.submit(()->{
                DnsUpstream up=new DnsUpstream(NetworkProfiles.protector(context),new String[]{ip},53);probes.add(up);long start=System.nanoTime();
                try{if(closed)throw new IOException("Отменено");byte[] reply=up.resolve(DnsPackets.probe(0x7101));if((reply[3]&15)!=0 || DnsPackets.u16(reply,6)==0)throw new IOException("Нет DNS-ответа");return new Node(ip,System.nanoTime()-start);}
                finally{probes.remove(up);up.close();}
            });
        }
        ArrayList<Node> good=new ArrayList<>();long deadline=System.nanoTime()+3_500_000_000L;
        for(int i=0;i<count && !closed;i++){
            long left=deadline-System.nanoTime();if(left<=0)break;
            Future<Node> ready=completed.poll(left,TimeUnit.NANOSECONDS);if(ready==null)break;
            try{good.add(ready.get());}catch(ExecutionException ignored){}
        }
        if(closed)throw new IOException("Отменено");
        if(good.isEmpty())return "\nGeoHide: доступные UDP-узлы не найдены. Прежний список сохранён; попробуйте HTTPS или другую сеть.\n";
        good.sort(Comparator.comparingLong(n->n.nanos));
        LinkedHashSet<String> ranked=new LinkedHashSet<>();for(Node n:good)ranked.add(n.ip);ranked.addAll(candidates);
        DnsCatalog.saveGeoHide(context,ranked.stream().limit(24).toArray(String[]::new));
        return "\nGeoHide: ответили "+good.size()+" узла. Быстрые адреса поставлены первыми.\nЭто проверка DNS-ответов, а не скорости загрузки сайтов.\n";
    }
    private static final class Node {final String ip;final long nanos;Node(String ip,long nanos){this.ip=ip;this.nanos=nanos;}}
    @Override public void close(){
        closed=true;Call pending=call;if(pending!=null)pending.cancel();for(DnsUpstream up:probes)up.close();probes.clear();pool.shutdownNow();
        OkHttpClient client=http;if(client!=null)IoCleanup.run(()->{client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();});
    }
}
