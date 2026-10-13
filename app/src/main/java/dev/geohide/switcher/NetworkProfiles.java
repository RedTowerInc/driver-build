package dev.geohide.switcher;
import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
final class NetworkProfiles {
    static volatile String testKind;
    private static Network cached;
    private static String cachedKind="other";
    private static boolean cachedPrivate;
    private static long fresh=-1000;
    static synchronized void invalidate(){fresh=-1000;}
    static synchronized Network physical(Context c){
        long now=android.os.SystemClock.elapsedRealtime();if(now-fresh<500)return cached;
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network active=cm.getActiveNetwork();NetworkCapabilities activeCap=active==null?null:cm.getNetworkCapabilities(active);boolean vpn=activeCap!=null && activeCap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        Network best=null;NetworkCapabilities bestCap=null;int score=-1;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap==null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
            boolean valid=cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),wifi=cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            int value=(n.equals(active)?1000:0)+(valid?100:0)+(wifi?10:0);
            // A DNS VPN becomes the default network of its own UID. Preserve the
            // previously selected live physical network instead of treating it as lost.
            if(vpn && n.equals(cached))value+=900;
            if(vpn && "mobile".equals(cachedKind) && wifi && valid)value+=950;
            if(value>score){best=n;bestCap=cap;score=value;}
        }
        cached=best;cachedKind=bestCap!=null && bestCap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"wifi":bestCap!=null && bestCap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":"other";
        LinkProperties lp=best==null?null:cm.getLinkProperties(best);cachedPrivate=lp!=null && lp.getPrivateDnsServerName()!=null;fresh=now;return best;
    }
    static String kind(Context c){if(BuildConfig.DEBUG && testKind!=null)return testKind;physical(c);synchronized(NetworkProfiles.class){return cachedKind;}}
    static boolean strictPrivateDns(Context c){physical(c);synchronized(NetworkProfiles.class){return cachedPrivate;}}
    static String label(String kind){return "wifi".equals(kind)?"Wi-Fi":"mobile".equals(kind)?"Мобильная сеть":"Другая сеть";}
    static DnsUpstream.Protector protector(Context c){return new DnsUpstream.Protector(){
        public boolean tcp(Socket s){try{if(!s.isBound())s.bind(new InetSocketAddress(0));Network n=physical(c);if(n==null)return false;n.bindSocket(s);DnsVpnService service=DnsVpnService.instance;return service==null || service.protect(s);}catch(IOException e){return false;}}
        public boolean udp(DatagramSocket s){try{DnsVpnService service=DnsVpnService.instance;if(service!=null && !service.protect(s))return false;Network n=physical(c);if(n!=null)n.bindSocket(s);return n!=null;}catch(IOException e){return false;}}
    };}
}
