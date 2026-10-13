package dev.geohide.switcher;
import android.content.Context;
import android.content.SharedPreferences;
final class DnsSettings {
    private static SharedPreferences prefs(Context c){
        SharedPreferences s=c.getSharedPreferences("dns_profiles",Context.MODE_PRIVATE);
        if(s.getInt("transport_defaults_version",0)<1){
            SharedPreferences.Editor edit=s.edit().putInt("transport_defaults_version",1);
            // Migrate the HTTPS default of 2.1.0 once; later manual choices remain valid.
            if("DOH".equals(s.getString("transport_dns_ai",null)))edit.putString("transport_dns_ai","DOT");
            edit.apply();
        }
        return s;
    }
    static DnsProfiles.Selection load(Context c){SharedPreferences s=prefs(c);DnsProfiles.Profile p=DnsProfiles.find(s.getString("profile","geohide"));return new DnsProfiles.Selection(p,DnsProfiles.normalize(p,s.getString("transport_"+p.id,p.initial.name())));}
    static void save(Context c,DnsProfiles.Selection s){prefs(c).edit().putString("profile",s.profile.id).putString("transport_"+s.profile.id,s.transport.name()).apply();}
    static DnsProfiles.Selection select(Context c,String id){
        DnsProfiles.Profile p=DnsProfiles.find(id);
        // A profile selection always applies its standard mode, independent of the previous provider.
        DnsProfiles.Selection s=new DnsProfiles.Selection(p,p.initial);save(c,s);return s;
    }
}
