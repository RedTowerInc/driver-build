package dev.geohide.switcher;
import android.content.Context;
import android.security.keystore.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
final class ProxyStore {
    private static final String ALIAS="GeoHideProxyConfigV1";
    private static SecretKey key()throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
    static void save(Context c,String input)throws Exception{
        String config=ProxyConfig.parse(input).toString();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String value=Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(cipher.doFinal(config.getBytes(StandardCharsets.UTF_8)));
        c.getSharedPreferences("proxy",0).edit().putString("sealed",value).apply();
    }
    static String load(Context c)throws Exception{
        String value=c.getSharedPreferences("proxy",0).getString("sealed","");if(value.isEmpty())throw new IllegalStateException("Сначала импортируйте конфигурацию сервера");String[] fields=value.split(":",2);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(fields[0])));return new String(cipher.doFinal(Base64.getDecoder().decode(fields[1])),StandardCharsets.UTF_8);
    }
    static boolean exists(Context c){return c.getSharedPreferences("proxy",0).contains("sealed");}
    static void clear(Context c){c.getSharedPreferences("proxy",0).edit().clear().apply();}
}
