package dev.geohide.switcher;

import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded URI-list subscription. Never stores source URLs or credentials in diagnostics. */
final class ProxySubscription {
    static final int MAX_BYTES=1024*1024,MAX_PROFILES=1024;
    static final class Problem extends IOException {Problem(String message){super(message);}}
    static final class Entry {
        final String config,label;
        Entry(JSONObject outbound,String name,int index)throws Exception{
            config=outbound.toString();String type=outbound.getString("type");
            JSONObject transport=outbound.optJSONObject("transport"),tls=outbound.optJSONObject("tls");
            String wire=transport==null?"TCP":transport.optString("type").toUpperCase(Locale.ROOT);
            String security=tls!=null && tls.optJSONObject("reality")!=null?"REALITY":tls!=null?"TLS":"AEAD";
            label=index+". "+name+" · "+type.toUpperCase(Locale.ROOT)+" / "+wire+" / "+security;
        }
    }
    static final class Result {
        final List<Entry> entries=new ArrayList<>();
        final Map<String,Integer> skipped=new LinkedHashMap<>();int duplicates,total;
        int skippedCount(){int n=0;for(int x:skipped.values())n+=x;return n;}
        String summary(){StringBuilder b=new StringBuilder("Совместимых серверов: ").append(entries.size()).append(". Совместимость формата не гарантирует доступность сервера.\n");
            for(Map.Entry<String,Integer> e:skipped.entrySet())b.append(e.getValue()).append(" · ").append(e.getKey()).append('\n');
            if(duplicates>0)b.append("Повторов: ").append(duplicates).append('\n');return b.toString();}
    }
    static String clean(String value){int a=0,b=value.length();while(a<b && space(value.charAt(a)))a++;while(b>a && space(value.charAt(b-1)))b--;return value.substring(a,b);}
    private static boolean space(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c=='\ufeff';}
    static boolean isUrl(String text){String t=clean(text).toLowerCase(Locale.ROOT);return t.startsWith("https://")||t.startsWith("http://");}
    static String https(String text)throws Problem{
        String value=clean(text);
        try{URI uri=new URI(value);if(!"https".equalsIgnoreCase(uri.getScheme()))throw new Problem("Для подписки нужна ссылка HTTPS.");
            if(uri.getHost()==null || uri.getHost().isEmpty() || uri.getRawUserInfo()!=null || uri.getPort()>65535 || uri.getPort()==0)throw new Problem("Проверьте HTTPS-ссылку на подписку.");
            return value;
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("Проверьте HTTPS-ссылку на подписку.");}
    }
    static byte[] read(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=input.read(b))!=-1){if(out.size()+n>MAX_BYTES)throw new Problem("Подписка больше 1 МБ.");out.write(b,0,n);}return out.toByteArray();
    }
    private static String utf8(byte[] bytes)throws Problem{try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new Problem("Подписка должна содержать текст UTF-8.");}}
    static Result parse(byte[] bytes)throws Exception{
        if(bytes.length>MAX_BYTES)throw new Problem("Подписка больше 1 МБ.");String text=clean(utf8(bytes));
        if(text.isEmpty())throw new Problem("Подписка пустая.");
        if(text.startsWith("<"))throw new Problem("Ссылка вернула веб-страницу, а не подписку серверов.");
        if(text.startsWith("{") || text.startsWith("["))throw new Problem("Нужна подписка со ссылками серверов, а не полный JSON-конфиг.");
        if(!text.contains("://")){
            try{text=clean(utf8(Base64.getDecoder().decode(text.replaceAll("\\s", "").replace('-','+').replace('_','/'))));}
            catch(IllegalArgumentException e){throw new Problem("Не удалось распознать текстовую или Base64-подписку.");}
        }
        if(text.startsWith("<"))throw new Problem("Ссылка вернула веб-страницу, а не подписку серверов.");
        Result result=new Result();Set<String> seen=new HashSet<>();
        String[] lines=text.split("[\\r\\n]+");if(lines.length>8192)throw new Problem("В подписке слишком много строк.");
        for(String line:lines){line=clean(line);if(line.isEmpty() || line.startsWith("#") || line.startsWith("https://") || line.startsWith("http://"))continue;
            if(!line.contains("://"))continue;
            if(++result.total>MAX_PROFILES)throw new Problem("В подписке больше 1024 профилей.");
            try{JSONObject outbound=ProxyConfig.parse(line);String canonical=outbound.toString();if(!seen.add(canonical)){result.duplicates++;continue;}
                String name="Сервер";try{String fragment=new URI(line).getRawFragment();if(fragment!=null && !fragment.isEmpty())name=ProxyConfig.decode(fragment).replaceAll("[\\p{Cntrl}]", " ");}catch(Exception ignored){}
                name=name.trim();if(name.isEmpty() || name.contains("://") || name.contains("@"))name="Сервер";if(name.length()>70)name=name.substring(0,70);
                result.entries.add(new Entry(outbound,name,result.entries.size()+1));
            }catch(Exception e){String reason=error(e);result.skipped.put(reason,result.skipped.getOrDefault(reason,0)+1);}
        }
        if(result.total==0)throw new Problem("В ответе нет ссылок ss://, vless:// или trojan://.");
        if(result.entries.isEmpty())throw new Problem("В подписке нет совместимых серверов.\n"+result.summary());return result;
    }
    private static boolean hasTlsError(Throwable error){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());Deque<Throwable> queue=new ArrayDeque<>();queue.add(error);
        while(!queue.isEmpty() && seen.size()<32){Throwable t=queue.removeFirst();if(!seen.add(t))continue;if(t instanceof javax.net.ssl.SSLException)return true;if(t.getCause()!=null)queue.add(t.getCause());for(Throwable suppressed:t.getSuppressed())queue.add(suppressed);}return false;
    }
    static String error(Exception e){
        if(e instanceof ProxyConfig.Invalid || e instanceof Problem)return e.getMessage();
        if(hasTlsError(e))return "Не удалось проверить TLS-сертификат подписки.";
        if(e instanceof java.net.SocketTimeoutException)return "Сервер подписки не ответил вовремя.";
        if(e instanceof IOException)return "Не удалось загрузить подписку. Проверьте сеть и ссылку.";
        return "Проверьте формат ключа и параметры сервера.";
    }
}
