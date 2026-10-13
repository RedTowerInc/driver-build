package dev.geohide.switcher;

import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

/** One-shot HTTPS fetch with strict trust, bounded body/redirects and cancellation. */
final class SubscriptionLoader implements AutoCloseable {
    private final OkHttpClient client;
    private volatile Call pending;
    private volatile boolean cancelled;
    SubscriptionLoader(){this(null);}
    SubscriptionLoader(OkHttpClient fixture){client=(fixture==null?new OkHttpClient.Builder():fixture.newBuilder()).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(7,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();}
    ProxySubscription.Result load(String input)throws Exception{
        String url=ProxySubscription.https(input);long deadline=System.nanoTime()+20_000_000_000L;
        try{
            for(int redirects=0;redirects<=3;redirects++){
                if(cancelled)throw new IOException("Cancelled");long remaining=deadline-System.nanoTime();if(remaining<=0)throw new SocketTimeoutException();
                Call call=client.newBuilder().callTimeout(Math.min(remaining,15_000_000_000L),TimeUnit.NANOSECONDS).build().newCall(new Request.Builder().url(url).header("User-Agent","GeoHideSwitch/2.2.1").header("Accept","text/plain, application/octet-stream;q=0.9, */*;q=0.1").build());
                synchronized(this){if(cancelled){call.cancel();throw new IOException("Cancelled");}pending=call;}
                try(Response response=call.execute()){
                    int code=response.code();
                    if(code==301 || code==302 || code==303 || code==307 || code==308){
                        if(redirects==3)throw new ProxySubscription.Problem("Слишком много перенаправлений подписки.");String location=response.header("Location");
                        if(location==null)throw new ProxySubscription.Problem("Сервер не указал адрес перенаправления.");
                        try{url=ProxySubscription.https(new URI(url).resolve(location).toString());}catch(ProxySubscription.Problem e){throw e;}catch(Exception e){throw new ProxySubscription.Problem("Некорректное перенаправление подписки.");}continue;
                    }
                    if(code!=200)throw new ProxySubscription.Problem("Сервер подписки ответил HTTP "+code+".");
                    if(response.body()==null)throw new ProxySubscription.Problem("Подписка пустая.");
                    if(response.body().contentLength()>ProxySubscription.MAX_BYTES)throw new ProxySubscription.Problem("Подписка больше 1 МБ.");
                    try(InputStream in=response.body().byteStream()){byte[] body=ProxySubscription.read(in);if(cancelled)throw new IOException("Cancelled");return ProxySubscription.parse(body);}
                }finally{pending=null;}
            }
            throw new ProxySubscription.Problem("Не удалось загрузить подписку.");
        }finally{close();}
    }
    @Override public void close(){Call call;synchronized(this){cancelled=true;call=pending;pending=null;}IoCleanup.run(()->{if(call!=null)call.cancel();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();});}
}
