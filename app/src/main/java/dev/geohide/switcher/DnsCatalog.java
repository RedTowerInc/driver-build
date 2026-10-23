package dev.geohide.switcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import org.json.*;

final class DnsCatalog {
    private static boolean loaded;
    private static long serial=DnsEndpointManifest.BUILTIN_SERIAL;
    private static String date=DnsEndpointManifest.BUILTIN_DATE;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("dns_catalog",Context.MODE_PRIVATE);}
    static synchronized void initialize(Context c){
        if(loaded)return;
        loaded=true;SharedPreferences p=prefs(c);
        try{String text=p.getString("dns_ai_manifest",null);if(text!=null){DnsEndpointManifest m=DnsEndpointManifest.parse(text,serial);DnsProfiles.addresses("dns_ai",m.addresses);serial=m.serial;date=m.updated;}}catch(Exception ignored){}
        try{String text=p.getString("geohide_addresses",null);if(text!=null){JSONArray a=new JSONArray(text);if(a.length()==0 || a.length()>24)throw new IOException();String[] ips=new String[a.length()];for(int i=0;i<ips.length;i++)ips[i]=DnsEndpointManifest.publicAddress(a.getString(i)).getHostAddress();DnsProfiles.addresses("geohide",ips);}}catch(Exception ignored){}
    }
    static synchronized long serial(Context c){initialize(c);return serial;}
    static synchronized String date(Context c){initialize(c);return date;}
    static synchronized void save(Context c,DnsEndpointManifest m)throws IOException{
        initialize(c);if(m.serial<serial)throw new IOException("Не применён устаревший список DNS");
        if(!prefs(c).edit().putString("dns_ai_manifest",m.json).putLong("checked",System.currentTimeMillis()).commit())throw new IOException("Не удалось сохранить настройки DNS");
        DnsProfiles.addresses("dns_ai",m.addresses);serial=m.serial;date=m.updated;
    }
    static synchronized void saveGeoHide(Context c,String[] ips)throws IOException{
        if(ips.length==0 || ips.length>24)throw new IOException("Не получен список GeoHide");
        JSONArray values=new JSONArray();for(String ip:ips)values.put(DnsEndpointManifest.publicAddress(ip).getHostAddress());
        if(!prefs(c).edit().putString("geohide_addresses",values.toString()).putLong("geohide_checked",System.currentTimeMillis()).commit())throw new IOException("Не удалось сохранить GeoHide");
        DnsProfiles.addresses("geohide",ips);
    }
    static String summary(Context c){initialize(c);return "DNS-AI: список от "+date(c)+" · "+DnsProfiles.find("dns_ai").addresses.length+" узла\nОбновление не меняет выбранный DNS и транспорт.";}
}
