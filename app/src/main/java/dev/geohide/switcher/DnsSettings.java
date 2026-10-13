package dev.geohide.switcher;
import android.content.Context;
import android.content.SharedPreferences;
final class DnsSettings {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("dns_profiles",Context.MODE_PRIVATE);}
    static DnsProfiles.Selection load(Context c){SharedPreferences s=prefs(c);DnsProfiles.Profile p=DnsProfiles.find(s.getString("profile","geohide"));return new DnsProfiles.Selection(p,DnsProfiles.normalize(p,s.getString("transport_"+p.id,p.initial.name())));}
    static void save(Context c,DnsProfiles.Selection s){prefs(c).edit().putString("profile",s.profile.id).putString("transport_"+s.profile.id,s.transport.name()).apply();}
    static DnsProfiles.Selection select(Context c,String id){DnsProfiles.Profile p=DnsProfiles.find(id);DnsProfiles.Selection s=new DnsProfiles.Selection(p,DnsProfiles.normalize(p,prefs(c).getString("transport_"+p.id,p.initial.name())));save(c,s);return s;}
}
