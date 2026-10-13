package dev.geohide.switcher;

import java.net.URI;
import java.util.Arrays;

/** Official endpoints checked on 2026-10-13. A profile never falls back to another provider. */
final class DnsProfiles {
    enum Transport {
        AUTO("Авто · HTTPS/TLS"), DOH("HTTPS · шифрование"), DOT("TLS · шифрование"), UDP("UDP · без шифрования");
        final String label;Transport(String label){this.label=label;}
    }
    static final class Profile {
        final String id,name,description,tlsHost,dohUrl,website;
        final String[] addresses;
        final boolean plainAllowed;
        final Transport initial;
        Profile(String id,String name,String description,String tlsHost,String dohUrl,String website,String[] addresses,boolean plain,Transport initial){
            this.id=id;this.name=name;this.description=description;this.tlsHost=tlsHost;this.dohUrl=dohUrl;this.website=website;
            this.addresses=addresses.clone();plainAllowed=plain;this.initial=initial;
        }
        boolean supports(Transport t){return t!=Transport.UDP || plainAllowed;}
        Transport[] transports(){return plainAllowed?Transport.values():new Transport[]{Transport.AUTO,Transport.DOH,Transport.DOT};}
        String dohHost(){return URI.create(dohUrl).getHost();}
        @Override public String toString(){return name;}
    }
    static final Profile[] ALL={
        new Profile("geohide","GeoHide","Доступ к выбранным зарубежным сайтам через Smart DNS.","dns.geohide.ru","https://dns.geohide.ru/dns-query","https://geohide.ru/",
            new String[]{"193.233.112.68","193.233.112.67","193.233.112.88","45.155.204.190","37.230.192.51","46.8.158.6","159.194.200.33"},true,Transport.UDP),
        new Profile("dns_ai","DNS-AI · dns-ai.ru","AI-сервисы для РФ/Беларуси, фильтрация рекламы и трекеров. Только шифрованное подключение.","dns.dns-ai.ru","https://dns.dns-ai.ru/dns-query","https://dns-ai.ru/",
            new String[]{"192.144.59.14","186.246.49.127","2a0d:8480:0:67c::14","2a0a:2b41:0:500d::53"},false,Transport.DOT),
        new Profile("cloudflare","Cloudflare 1.1.1.1","Обычный публичный DNS без фильтра рекламы. Не предназначен для обхода геоблокировок.","one.one.one.one","https://cloudflare-dns.com/dns-query","https://developers.cloudflare.com/1.1.1.1/",
            new String[]{"1.1.1.1","1.0.0.1","2606:4700:4700::1111","2606:4700:4700::1001"},true,Transport.DOH),
        new Profile("adguard","AdGuard DNS","Фильтрация рекламных, отслеживающих и опасных доменов. Не удаляет всю рекламу внутри приложений.","dns.adguard-dns.com","https://dns.adguard-dns.com/dns-query","https://adguard-dns.io/en/public-dns.html",
            new String[]{"94.140.14.14","94.140.15.15","2a10:50c0::ad1:ff","2a10:50c0::ad2:ff"},true,Transport.DOH),
        new Profile("quad9","Quad9","Блокировка известных вредоносных доменов и DNSSEC. Не предназначен для обхода геоблокировок.","dns.quad9.net","https://dns.quad9.net/dns-query","https://docs.quad9.net/services/",
            new String[]{"9.9.9.9","149.112.112.112","2620:fe::fe","2620:fe::9"},true,Transport.DOH)
    };
    static Profile find(String id){for(Profile p:ALL)if(p.id.equals(id))return p;return ALL[0];}
    static Transport normalize(Profile p,String mode){try{Transport t=Transport.valueOf(mode);if(p.supports(t))return t;}catch(Exception ignored){}return p.initial;}
    static final class Selection {
        final Profile profile;final Transport transport;
        Selection(Profile p,Transport t){if(!p.supports(t))throw new IllegalArgumentException("Этот сервер не поддерживает обычный DNS");profile=p;transport=t;}
        String label(){return profile.name+" · "+transport.label;}
    }
}
