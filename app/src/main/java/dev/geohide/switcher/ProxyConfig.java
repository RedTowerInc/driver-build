package dev.geohide.switcher;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
final class ProxyConfig {
    static final class Invalid extends IllegalArgumentException {Invalid(String message){super(message);}}
    private static final Set<String> METHODS=new HashSet<>(Arrays.asList("aes-128-gcm","aes-192-gcm","aes-256-gcm","chacha20-ietf-poly1305","xchacha20-ietf-poly1305","2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","2022-blake3-chacha20-poly1305"));
    static String decode(String value){try{return URLDecoder.decode(value.replace("+","%2B"),"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}}
    private static String b64(String value){return new String(Base64.getDecoder().decode(value.replace('-','+').replace('_','/')),StandardCharsets.UTF_8);}
    static JSONObject parse(String input)throws Exception{
        input=ProxySubscription.clean(input);if(input.length()>32768)throw new Invalid("Конфигурация слишком большая");
        JSONObject out;
        if(input.startsWith("{"))out=new JSONObject(input);
        else{
            if(input.startsWith("ss://") && !input.substring(5).split("#",2)[0].contains("@")){String raw=input.substring(5).split("#",2)[0];String decoded=b64(raw);int at=decoded.lastIndexOf('@');if(at<1)throw new Invalid("Неверная ссылка SS");input="ss://"+java.net.URLEncoder.encode(decoded.substring(0,at),"UTF-8").replace("+","%20")+decoded.substring(at);}
            URI uri=new URI(input);String scheme=uri.getScheme();String host=uri.getHost();int port=uri.getPort();
            if(host==null || port<1 || port>65535 || uri.getRawUserInfo()==null)throw new Invalid("Нужна ссылка с сервером, портом и учётными данными");
            Map<String,String> query=new HashMap<>();if(uri.getRawQuery()!=null)for(String field:uri.getRawQuery().split("&")){String[] pair=field.split("=",2);query.put(decode(pair[0]),pair.length>1?decode(pair[1]):"");}
            out=new JSONObject().put("server",host).put("server_port",port);
            String user=decode(uri.getRawUserInfo());
            if("ss".equals(scheme)){
                if(query.containsKey("plugin"))throw new Invalid("Плагины SS в этом импорте не поддерживаются");
                if(!user.contains(":"))user=b64(user);int split=user.indexOf(':');if(split<1)throw new Invalid("Неверные учётные данные SS");
                out.put("type","shadowsocks").put("method",user.substring(0,split)).put("password",user.substring(split+1));
            }else if("vless".equals(scheme) || "trojan".equals(scheme)){
                boolean vless="vless".equals(scheme);out.put("type",scheme).put(vless?"uuid":"password",user);
                String security=query.getOrDefault("security",vless?"none":"tls");if(!"tls".equals(security) && !"reality".equals(security))throw new Invalid("VLESS/Trojan требует TLS либо REALITY");
                if(insecure(query.get("allowInsecure")) || insecure(query.get("insecure")) || insecure(query.get("skip-cert-verify")))throw new Invalid("Отключать проверку сертификата нельзя");
                JSONObject tls=new JSONObject().put("enabled",true).put("server_name",query.getOrDefault("sni",query.getOrDefault("peer",host)));
                if(query.containsKey("alpn"))tls.put("alpn",new JSONArray(Arrays.asList(query.get("alpn").split(","))));
                String fingerprint=query.getOrDefault("fp","chrome");
                if("reality".equals(security)){tls.put("reality",new JSONObject().put("enabled",true).put("public_key",query.getOrDefault("pbk","")).put("short_id",query.getOrDefault("sid","")));tls.put("utls",new JSONObject().put("enabled",true).put("fingerprint",fingerprint));}
                if(query.containsKey("fp") && !tls.has("utls"))tls.put("utls",new JSONObject().put("enabled",true).put("fingerprint",fingerprint));
                out.put("tls",tls);if(vless && query.containsKey("flow") && !query.get("flow").isEmpty())out.put("flow",query.get("flow"));
                String type=query.getOrDefault("type","tcp");if("raw".equals(type))type="tcp";
                if("xhttp".equals(type))throw new Invalid("XHTTP не поддерживается текущим VPN-ядром.");
                if(!query.getOrDefault("headerType","").isEmpty() && !"none".equals(query.get("headerType")))throw new Invalid("TCP с дополнительным HTTP-заголовком не поддерживается.");
                if("ws".equals(type)){JSONObject ws=new JSONObject().put("type","ws").put("path",query.getOrDefault("path","/"));if(query.containsKey("host"))ws.put("headers",new JSONObject().put("Host",query.get("host")));out.put("transport",ws);}
                else if("grpc".equals(type))out.put("transport",new JSONObject().put("type","grpc").put("service_name",query.getOrDefault("serviceName","")));
                else if(!"tcp".equals(type))throw new Invalid("Поддерживаются TCP, WebSocket и gRPC");
            }else throw new Invalid("Поддерживаются ss://, vless:// и trojan://");
        }
        validate(out);out.put("tag","proxy");return out;
    }
    private static boolean insecure(String value){return "1".equals(value) || "true".equalsIgnoreCase(value);}
    static void validate(JSONObject out)throws Exception{
        Set<String> fields=new HashSet<>(Arrays.asList("type","tag","server","server_port","method","password","uuid","flow","tls","transport","multiplex","udp_over_tcp"));
        Iterator<String> keys=out.keys();while(keys.hasNext())if(!fields.contains(keys.next()))throw new Invalid("Импортируется один outbound, а не полный конфиг sing-box");
        String type=out.optString("type");if(!Arrays.asList("shadowsocks","vless","trojan").contains(type))throw new Invalid("Неподдерживаемый тип прокси");
        if(out.optString("server").trim().isEmpty() || out.optInt("server_port")<1 || out.optInt("server_port")>65535)throw new Invalid("Неверный сервер или порт");
        if("shadowsocks".equals(type)){
            if(!METHODS.contains(out.optString("method")))throw new Invalid("Требуется современное AEAD-шифрование Shadowsocks");
            if(out.optString("password").isEmpty())throw new Invalid("Не задан пароль");
            if(out.optString("method").startsWith("2022-")){int length=out.getString("method").contains("aes-128")?16:32;for(String key:out.getString("password").split(":",-1))if(Base64.getDecoder().decode(key).length!=length)throw new Invalid("Неверная длина ключа Shadowsocks 2022");}
        }else{
            if("vless".equals(type))UUID.fromString(out.getString("uuid"));else if(out.optString("password").isEmpty())throw new Invalid("Не задан пароль");
            JSONObject tls=out.optJSONObject("tls");if(tls==null || !tls.optBoolean("enabled") || tls.optBoolean("insecure"))throw new Invalid("Требуется TLS с проверкой сертификата");
            Set<String> tlsFields=new HashSet<>(Arrays.asList("enabled","server_name","alpn","min_version","max_version","utls","reality"));for(Iterator<String> i=tls.keys();i.hasNext();)if(!tlsFields.contains(i.next()))throw new Invalid("Неподдерживаемая настройка TLS");
            if(tls.has("min_version") && !Arrays.asList("1.2","1.3").contains(tls.getString("min_version")))throw new Invalid("Требуется TLS 1.2 или новее");
            if(tls.has("max_version") && !Arrays.asList("1.2","1.3").contains(tls.getString("max_version")))throw new Invalid("Требуется TLS 1.2 или новее");
            if(tls.optString("server_name").isEmpty())tls.put("server_name",out.getString("server"));
            JSONObject reality=tls.optJSONObject("reality");if(reality!=null && reality.optBoolean("enabled")){
                if(!"vless".equals(type))throw new Invalid("REALITY поддерживается для VLESS");
                String key=reality.optString("public_key");if(Base64.getUrlDecoder().decode(key).length!=32 || !reality.optString("short_id").matches("(?:[0-9a-fA-F]{2}){0,8}"))throw new Invalid("Неверный ключ или short ID REALITY");
            }
        }
        JSONObject transport=out.optJSONObject("transport");if(transport!=null && !Arrays.asList("ws","grpc","http").contains(transport.optString("type")))throw new Invalid("Неподдерживаемый транспорт");
    }
    static JSONObject client(JSONObject outbound,int port,String user,String password)throws Exception{
        JSONObject proxy=new JSONObject(outbound.toString()).put("tag","proxy");
        JSONObject inbound=new JSONObject().put("type","mixed").put("tag","local").put("listen","127.0.0.1").put("listen_port",port).put("users",new JSONArray().put(new JSONObject().put("username",user).put("password",password)));
        JSONObject dnsRule=new JSONObject().put("ip_cidr",new JSONArray().put("10.77.77.1/32")).put("port",53).put("action","hijack-dns");
        JSONObject dnsServer=new JSONObject().put("type","https").put("tag","vpn-dns").put("server","1.1.1.1").put("path","/dns-query").put("tls",new JSONObject().put("enabled",true).put("server_name","cloudflare-dns.com")).put("detour","proxy");
        return new JSONObject().put("log",new JSONObject().put("disabled",true)).put("inbounds",new JSONArray().put(inbound)).put("outbounds",new JSONArray().put(proxy)).put("route",new JSONObject().put("rules",new JSONArray().put(dnsRule)).put("final","proxy")).put("dns",new JSONObject().put("servers",new JSONArray().put(dnsServer)).put("final","vpn-dns"));
    }
}
