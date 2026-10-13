package dev.geohide.switcher;
import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
final class NetworkProfiles {
    static volatile String testKind;
    static Network physical(Context c){
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);Network active=cm.getActiveNetwork();Network best=null;int score=-1;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities cap=cm.getNetworkCapabilities(n);
            if(cap==null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
            int value=(n.equals(active)?1000:0)+(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?100:0)+(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?10:0);
            if(value>score){best=n;score=value;}
        }return best;
    }
    static String kind(Context c){if(BuildConfig.DEBUG && testKind!=null)return testKind;Network n=physical(c);if(n==null)return "other";NetworkCapabilities cap=c.getSystemService(ConnectivityManager.class).getNetworkCapabilities(n);if(cap==null)return "other";return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"wifi":cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"mobile":"other";}
    static String label(String kind){return "wifi".equals(kind)?"Wi-Fi":"mobile".equals(kind)?"Мобильная сеть":"Другая сеть";}
    static DnsUpstream.Protector protector(Context c){return new DnsUpstream.Protector(){
        public boolean tcp(Socket s){try{DnsVpnService service=DnsVpnService.instance;if(service!=null && !service.protect(s))return false;Network n=physical(c);if(n!=null)n.bindSocket(s);return n!=null;}catch(IOException e){return false;}}
        public boolean udp(DatagramSocket s){try{DnsVpnService service=DnsVpnService.instance;if(service!=null && !service.protect(s))return false;Network n=physical(c);if(n!=null)n.bindSocket(s);return n!=null;}catch(IOException e){return false;}}
    };}
}
