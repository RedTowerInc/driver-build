package dev.geohide.switcher;
import java.util.*;
final class EventLog {
    enum Result { OK, TIMEOUT, CERTIFICATE, TLS, CONNECT, DNS, HTTP_REFUSED, CANCELLED, IO, START, STOP, NETWORK }
    private static final ArrayDeque<String> entries=new ArrayDeque<>();
    static Result category(Throwable e){boolean tls=false;for(Throwable t=e;t!=null;t=t.getCause()){
        if(t instanceof java.net.SocketTimeoutException)return Result.TIMEOUT;
        if(t instanceof java.security.cert.CertificateException || t instanceof java.security.cert.CertPathValidatorException || t instanceof javax.net.ssl.SSLPeerUnverifiedException)return Result.CERTIFICATE;
        if(t instanceof javax.net.ssl.SSLException)tls=true;
        if(t instanceof java.net.UnknownHostException)return Result.DNS;if(t instanceof java.net.ConnectException)return Result.CONNECT;
    }return tls?Result.TLS:Result.IO;}
    static String label(Result r){switch(r){case TIMEOUT:return "Тайм-аут";case CERTIFICATE:return "Ошибка проверки сертификата";case TLS:return "Ошибка TLS-соединения";case CONNECT:return "TCP-соединение не установлено";case DNS:return "Не удалось получить DNS-адрес";case CANCELLED:return "Отменено";default:return "Ошибка сетевого обмена";}}
    static synchronized void record(String profile,String mode,Result result,long ms){
        String safeProfile="custom";for(DnsProfiles.Profile p:DnsProfiles.ALL)if(p.id.equals(profile))safeProfile=p.id;
        if("vpn".equals(profile))safeProfile="vpn";
        String safeMode="unknown";for(DnsProfiles.Transport t:DnsProfiles.Transport.values())if(t.name().equals(mode))safeMode=t.name();if("VPN".equals(mode))safeMode="VPN";
        String line=String.format(Locale.ROOT,"%tFT%<tT · %s · %s · %s · %d ms",new Date(),safeProfile,safeMode,result,Math.max(0,ms));
        entries.addLast(line);while(entries.size()>160)entries.removeFirst();
    }
    static synchronized String text(){return String.join("\n",entries);}
}
