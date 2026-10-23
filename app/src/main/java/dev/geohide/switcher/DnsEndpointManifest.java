package dev.geohide.switcher;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import org.json.*;

/** Data only: fixed provider hostname and transports cannot be changed by a downloaded list. */
final class DnsEndpointManifest {
    static final long BUILTIN_SERIAL=7;
    static final String BUILTIN_DATE="2026-10-18";
    final long serial;final String updated,json;final String[] addresses;
    private DnsEndpointManifest(long serial,String updated,String json,String[] addresses){this.serial=serial;this.updated=updated;this.json=json;this.addresses=addresses;}
    static DnsEndpointManifest parse(String text,long minimum)throws IOException{
        try{
            if(text.length()>65536)throw new IOException("Список DNS слишком большой");
            JSONObject root=new JSONObject(text);
            if(root.getInt("v")!=1)throw new IOException("Неизвестный формат списка DNS");
            long serial=root.getLong("serial");if(serial<minimum)throw new IOException("Источник вернул устаревший список DNS");
            String date=root.getString("updated");if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IOException("Неверная дата списка DNS");
            try{java.time.LocalDate.parse(date);}catch(java.time.format.DateTimeParseException e){throw new IOException("Неверная дата списка DNS",e);}
            LinkedHashSet<String> addresses=new LinkedHashSet<>();
            for(String family:new String[]{"v4","v6"}){
                JSONArray values=root.getJSONArray(family);if(values.length()>8)throw new IOException("Слишком много DNS-узлов");
                for(int i=0;i<values.length();i++){
                    Object item=values.get(i);if(!(item instanceof String))throw new IOException("IP DNS должен быть строкой");
                    String ip=(String)item;InetAddress parsed=publicAddress(ip);
                    if((parsed.getAddress().length==4)!=family.equals("v4"))throw new IOException("Неверное семейство DNS-адреса");
                    addresses.add(parsed.getHostAddress());
                }
            }
            if(root.getJSONArray("v4").length()==0 || addresses.isEmpty())throw new IOException("Пустой список DNS-узлов");
            return new DnsEndpointManifest(serial,date,root.toString(),addresses.toArray(new String[0]));
        }catch(JSONException | IllegalArgumentException e){throw new IOException("Не удалось прочитать список DNS",e);}
    }
    static InetAddress publicAddress(String ip)throws IOException{
        if(ip==null || ip.isEmpty() || ip.length()>45 || ip.contains("%"))throw new IOException("Некорректный IP DNS");
        if(ip.contains(":")){if(!ip.matches("[0-9a-fA-F:.]+"))throw new IOException("Ожидался IPv6-адрес");}
        else{
            if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw new IOException("Ожидался IPv4-адрес");
            for(String part:ip.split("\\."))if(Integer.parseInt(part)>255 || (part.length()>1 && part.charAt(0)=='0'))throw new IOException("Неверный IPv4-адрес");
        }
        InetAddress result=InetAddress.getByName(ip);byte[] bytes=result.getAddress();
        if(result.isAnyLocalAddress() || result.isLoopbackAddress() || result.isLinkLocalAddress() || result.isSiteLocalAddress() || result.isMulticastAddress() || (bytes.length==16 && (bytes[0]&0xfe)==0xfc) || (bytes.length==4 && ((bytes[0]&255)==0 || (bytes[0]&255)>=224)))throw new IOException("Вместо публичного DNS получен локальный адрес");
        return result;
    }
}
