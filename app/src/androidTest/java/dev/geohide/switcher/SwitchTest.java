package dev.geohide.switcher;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class SwitchTest {
    private static final String PKG="dev.geohide.switcher";
    private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    private final Context ctx=ins.getTargetContext();
    private UiDevice device;
    private DatagramSocket fake;
    private volatile boolean silent;
    private Thread responder;
    private Activity activity;
    private void waitState(DnsVpnService.State state)throws Exception{
        long end=System.currentTimeMillis()+8000;
        while(System.currentTimeMillis()<end){if(DnsVpnService.current().state==state)return;Thread.sleep(50);}
        fail("Expected "+state+", got "+DnsVpnService.current().state+" "+DnsVpnService.current().detail);
    }
    private void click(String id)throws Exception{
        new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().resourceId(PKG+":id/"+id));
        UiObject2 v=device.wait(Until.findObject(By.res(PKG,id)),4000);assertNotNull(id,v);v.click();device.waitForIdle();
    }
    private void tap(int id){ins.runOnMainSync(()->{Activity live=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();assertTrue(live.findViewById(id).performClick());});}
    private void grant()throws Exception{
        UiObject2 yes=device.wait(Until.findObject(By.res("android","button1")),4000);
        if(yes!=null)yes.click();
    }
    private void screenshot(String name)throws Exception{
        device.executeShellCommand("mkdir -p /sdcard/Download/GeoHide-QA");
        device.executeShellCommand("screencap -p /sdcard/Download/GeoHide-QA/"+name+".png");
    }
    @Before public void before()throws Exception{
        DnsSettings.save(ctx,new DnsProfiles.Selection(DnsProfiles.ALL[0],DnsProfiles.Transport.UDP));
        device=UiDevice.getInstance(ins);if(Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant "+PKG+" android.permission.POST_NOTIFICATIONS");device.wakeUp();device.setOrientationNatural();
        fake=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));fake.setSoTimeout(300);
        DnsVpnService.testServers=new String[]{"127.0.0.1"};DnsVpnService.testPort=fake.getLocalPort();
        responder=new Thread(()->{while(!fake.isClosed())try{
            byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);fake.receive(p);if(silent)continue;
            byte[] q=Arrays.copyOf(b,p.getLength());int end=DnsPackets.questionEnd(q);if(end<0)continue;
            boolean a=DnsPackets.u16(q,end-4)==1;byte[] r=Arrays.copyOf(q,end+(a?16:0));
            r[2]=(byte)0x81;r[3]=(byte)0x80;DnsPackets.p16(r,6,a?1:0);DnsPackets.p16(r,8,0);DnsPackets.p16(r,10,0);
            if(a){int n=end;r[n]=(byte)0xc0;r[n+1]=0x0c;r[n+3]=1;r[n+5]=1;r[n+9]=60;r[n+11]=4;
            r[n+12]=(byte)203;r[n+13]=0;r[n+14]=113;r[n+15]=7;}
            fake.send(new DatagramPacket(r,r.length,p.getSocketAddress()));
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(!fake.isClosed())throw new RuntimeException(e);}},"FakeDNS");responder.start();
        activity=ins.startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();
    }
    @After public void after()throws Exception{
        ctx.startService(new Intent(ctx,DnsVpnService.class).setAction("STOP"));fake.close();responder.join(1000);
        DnsVpnService.testServers=null;DnsVpnService.testUpstream=null;device.unfreezeRotation();ins.runOnMainSync(()->activity.finish());
    }
    @Test public void vpnSwitchAndDnsPacketEndToEnd()throws Exception{
        screenshot("01-off");
        // The permission dialog must not activate VPN when cancelled.
        if(VpnService.prepare(ctx)!=null){click("power");UiObject2 cancel=device.wait(Until.findObject(By.res("android","button2")),4000);assertNotNull(cancel);cancel.click();waitState(DnsVpnService.State.OFF);}
        click("power");grant();waitState(DnsVpnService.State.ON);Thread.sleep(500);screenshot("02-on");
        // A real packet enters Android's VPN TUN, reaches the upstream and comes back.
        try(DatagramSocket client=new DatagramSocket()){
            client.setSoTimeout(4000);byte[] q=DnsPackets.probe(1111);client.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));
            byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);client.receive(p);
            assertTrue(DnsPackets.matches(q,Arrays.copyOf(b,p.getLength())));assertEquals(7,b[p.getLength()-1]);
        }
        assertTrue(DnsVpnService.current().answered>0);
        ConnectivityManager cm=ctx.getSystemService(ConnectivityManager.class);
        Network vpn=null;
        for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))vpn=n;}
        assertNotNull(vpn);InetAddress[] systemAnswer=vpn.getAllByName("dnstest.geohide.ru");assertTrue(systemAnswer.length>0);
        // Loss and restoration of upstream response must be visible, with VPN still stoppable.
        silent=true;
        try(DatagramSocket client=new DatagramSocket()){
            client.setSoTimeout(4000);byte[] q=DnsPackets.probe(1112);client.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));
            byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);client.receive(p);assertEquals(2,b[3]&15);
        }
        waitState(DnsVpnService.State.WAITING);Thread.sleep(300);screenshot("04-no-dns-answer");silent=false;
        try(DatagramSocket client=new DatagramSocket()){
            client.setSoTimeout(4000);byte[] q=DnsPackets.probe(1113);client.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));
            byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);client.receive(p);assertEquals(0,b[3]&15);
        }
        waitState(DnsVpnService.State.ON);

        device.setOrientationLeft();Thread.sleep(800);assertEquals(DnsVpnService.State.ON,DnsVpnService.current().state);
        device.setOrientationNatural();Thread.sleep(800);
        // Resume must reflect the running service.
        device.pressHome();ctx.startActivity(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();
        assertTrue(device.wait(Until.hasObject(By.text("DNS ВКЛЮЧЁН")),4000));
        click("power");waitState(DnsVpnService.State.OFF);Thread.sleep(500);
        waitNoVpn();screenshot("03-off-after-stop");
        for(int i=0;i<5;i++){click("power");waitState(DnsVpnService.State.ON);click("power");waitState(DnsVpnService.State.OFF);}
        // Cancelling an unanswered startup must be immediate and never reconnect later.
        silent=true;tap(R.id.power);waitState(DnsVpnService.State.STARTING);long start=System.currentTimeMillis();tap(R.id.force_stop);waitState(DnsVpnService.State.OFF);
        assertTrue(System.currentTimeMillis()-start<2000);Thread.sleep(1200);assertEquals(DnsVpnService.State.OFF,DnsVpnService.current().state);waitNoVpn();
        // Verify the separate force-stop button through the physical UI as well.
        silent=false;click("power");waitState(DnsVpnService.State.ON);click("force_stop");waitState(DnsVpnService.State.OFF);waitNoVpn();
        // Rapid clicks cannot leave stale state or a VPN after forced stop.
        ins.runOnMainSync(()->{Activity live=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();View v=live.findViewById(R.id.power);for(int i=0;i<20;i++)v.performClick();});
        click("force_stop");waitState(DnsVpnService.State.OFF);Thread.sleep(500);waitNoVpn();
        click("power");waitState(DnsVpnService.State.ON);
        // Exercise onRevoke callback separately from normal onDestroy.
        ins.runOnMainSync(()->{assertNotNull(DnsVpnService.instance);DnsVpnService.instance.onRevoke();});waitState(DnsVpnService.State.OFF);waitNoVpn();
        assertFalse(hasOngoingNotification());
    }
    @Test public void profileChoicePersistenceAndEncryptedVpn()throws Exception{
        click("profile");UiObject2 ai=device.wait(Until.findObject(By.text("DNS-AI · dns-ai.ru")),3000);assertNotNull(ai);ai.click();device.waitForIdle();
        assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);assertEquals(DnsProfiles.Transport.DOH,DnsSettings.load(ctx).transport);
        click("transport");assertFalse(device.hasObject(By.text("UDP · без шифрования")));device.pressBack();
        device.setOrientationLeft();Thread.sleep(500);device.setOrientationNatural();Thread.sleep(500);
        assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);
        screenshot("05-dns-ai-selected");
        DnsVpnService.testServers=null;
        try(TlsFixture tls=new TlsFixture(ins.getContext().getAssets().open("dns-test.p12"),true)){
            DnsUpstream.Protector protector=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return DnsVpnService.instance!=null && DnsVpnService.instance.protect(s);}public boolean tcp(Socket s){return DnsVpnService.instance!=null && DnsVpnService.instance.protect(s);}};
            DnsVpnService.testUpstream=new DnsUpstream(protector,new DnsProfiles.Selection(tls.profile("localhost"),DnsProfiles.Transport.DOH),new String[]{"127.0.0.1"},tls.server.getLocalPort(),tls.client(),tls.context.getSocketFactory());
            click("power");grant();waitState(DnsVpnService.State.ON);assertEquals("dns_ai",DnsVpnService.current().choice.profile.id);
            UiObject2 picker=device.findObject(By.res(PKG,"profile"));assertNotNull(picker);assertFalse(picker.isEnabled());
            assertTrue(DnsVpnService.current().server.contains("HTTPS"));screenshot("06-dns-ai-https-active");
            try(DatagramSocket client=new DatagramSocket()){client.setSoTimeout(4000);byte[] q=DnsPackets.probe(151);client.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);client.receive(p);assertEquals(8,b[p.getLength()-1]);}
            assertTrue(tls.requests.get()>0);click("force_stop");waitState(DnsVpnService.State.OFF);waitNoVpn();
        }finally{DnsVpnService.testUpstream=null;}
        click("profile");UiObject2 cf=device.wait(Until.findObject(By.text("Cloudflare 1.1.1.1")),3000);assertNotNull(cf);cf.click();device.waitForIdle();assertEquals("cloudflare",DnsSettings.load(ctx).profile.id);
        DnsVpnService.testServers=new String[]{"127.0.0.1"};click("power");grant();waitState(DnsVpnService.State.ON);assertEquals("cloudflare",DnsVpnService.current().choice.profile.id);click("power");waitState(DnsVpnService.State.OFF);waitNoVpn();
    }
    private void waitNoVpn()throws Exception{long end=System.currentTimeMillis()+3000;while(vpnPresent() && System.currentTimeMillis()<end)Thread.sleep(50);assertFalse(vpnPresent());}
    private boolean vpnPresent(){ConnectivityManager cm=ctx.getSystemService(ConnectivityManager.class);for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return true;}return false;}
    private boolean hasOngoingNotification(){for(android.service.notification.StatusBarNotification n:ctx.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==1203)return true;return false;}
}
