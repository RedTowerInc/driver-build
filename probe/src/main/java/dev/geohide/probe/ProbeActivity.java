package dev.geohide.probe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.net.*;
import java.io.*;
public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);TextView text=new TextView(this);text.setTextSize(20);text.setText("VPN PROBE RUNNING");setContentView(text);
        new Thread(()->{String result;try{
            HttpURLConnection connection=(HttpURLConnection)new URL(getIntent().getStringExtra("url")).openConnection();connection.setConnectTimeout(6000);connection.setReadTimeout(6000);try{if(connection.getResponseCode()!=200)throw new IOException("HTTP status");String body=new String(connection.getInputStream().readNBytes(100),java.nio.charset.StandardCharsets.UTF_8);if(!body.contains("TUN-SS-OK"))throw new IOException("HTTP body");}finally{connection.disconnect();}
            byte[] dns=new byte[]{0x71,0x31,1,0,0,1,0,0,0,0,0,0,7,'e','x','a','m','p','l','e',3,'c','o','m',0,0,1,0,1};
            try(DatagramSocket socket=new DatagramSocket()){socket.setSoTimeout(6000);socket.send(new DatagramPacket(dns,dns.length,InetAddress.getByName("10.77.77.1"),53));byte[] buffer=new byte[1024];DatagramPacket packet=new DatagramPacket(buffer,buffer.length);socket.receive(packet);if(packet.getLength()<12 || buffer[0]!=dns[0] || buffer[1]!=dns[1] || (buffer[3]&15)!=0 || buffer[7]==0)throw new IOException("DNS reply");}
            result="VPN PROBE OK: TCP + UDP DNS";
        }catch(Exception e){result="VPN PROBE FAILED: "+e.getClass().getSimpleName();}String finalResult=result;runOnUiThread(()->text.setText(finalResult));},"TUN-client-other-UID").start();
    }
}
