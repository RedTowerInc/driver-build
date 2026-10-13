package dev.geohide.switcher;
import android.content.Context;
import android.content.SharedPreferences;
final class DnsSettings {
    private static SharedPreferences prefs(Context c){
        SharedPreferences s=c.getSharedPreferences("dns_profiles",Context.MODE_PRIVATE);
        if(s.getInt("transport_defaults_version",0)<1){SharedPreferences.Editor e=s.edit().putInt("transport_defaults_version",1);if("DOH".equals(s.getString("transport_dns_ai",null)))e.putString("transport_dns_ai","DOT");e.apply();}
        return s;
    }
    static DnsProfiles.Selection load(Context c){return loadFor(c,NetworkProfiles.kind(c));}
    static DnsProfiles.Selection loadFor(Context c,String kind){
        SharedPreferences s=prefs(c);String id=s.getString("profile_"+kind,s.getString("profile","geohide"));DnsProfiles.Profile p=DnsProfiles.find(id);
        return new DnsProfiles.Selection(p,DnsProfiles.normalize(p,s.getString("mode_"+kind+"_"+p.id,s.getString("transport_"+p.id,p.initial.name()))));
    }
    static void save(Context c,DnsProfiles.Selection choice){saveFor(c,NetworkProfiles.kind(c),choice);}
    static void saveFor(Context c,String kind,DnsProfiles.Selection choice){prefs(c).edit().putString("profile",choice.profile.id).putString("profile_"+kind,choice.profile.id).putString("transport_"+choice.profile.id,choice.transport.name()).putString("mode_"+kind+"_"+choice.profile.id,choice.transport.name()).apply();}
    static DnsProfiles.Selection select(Context c,String id){DnsProfiles.Profile p=DnsProfiles.find(id);DnsProfiles.Selection s=new DnsProfiles.Selection(p,p.initial);save(c,s);return s;}
    static DnsProfiles.Transport preferred(Context c,String kind,DnsProfiles.Profile p){DnsProfiles.Transport t=DnsProfiles.normalize(p,prefs(c).getString("good_"+kind+"_"+p.id,p.initial.name()));return t==DnsProfiles.Transport.DOH || t==DnsProfiles.Transport.DOT?t:null;}
    static void remember(Context c,String kind,DnsProfiles.Profile p,DnsProfiles.Transport t){if(t==DnsProfiles.Transport.DOH || t==DnsProfiles.Transport.DOT)prefs(c).edit().putString("good_"+kind+"_"+p.id,t.name()).apply();}
    static boolean fullVpn(Context c){return prefs(c).getBoolean("full_vpn",false);}
    static void fullVpn(Context c,boolean enabled){prefs(c).edit().putBoolean("full_vpn",enabled).apply();}
}
