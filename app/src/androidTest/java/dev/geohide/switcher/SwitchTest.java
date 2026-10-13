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
        fail("Expected "+state+", got "+DnsVpnService.current().state+" "+DnsVpnService.current().detail+"\n"+CoreProcess.testLog());
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
        DnsSettings.fullVpn(ctx,false);NetworkProfiles.testKind=null;DnsSettings.save(ctx,new DnsProfiles.Selection(DnsProfiles.ALL[0],DnsProfiles.Transport.UDP));
        device=UiDevice.getInstance(ins);if(Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant "+PKG+" android.permission.POST_NOTIFICATIONS");device.wakeUp();device.setOrientationNatural();
        fake=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));fake.setSoTimeout(300);
        DnsVpnService.testServers=new String[]{"127.0.0.1"};DnsVpnService.testPort=fake.getLocalPort();
        responder=new Thread(()->{while(!fake.isClosed())try{
            byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);fake.receive(p);if(silent)continue;
            byte[] q=Arrays.copyOf(b,p.getLength());if(DnsPackets.questionEnd(q)<0)continue;byte[] r=TlsFixture.response(q,7);
            fake.send(new DatagramPacket(r,r.length,p.getSocketAddress()));
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(!fake.isClosed())throw new RuntimeException(e);}},"FakeDNS");responder.start();
        activity=ins.startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();
    }
    @After public void after()throws Exception{
        ctx.startService(new Intent(ctx,DnsVpnService.class).setAction("STOP"));waitState(DnsVpnService.State.OFF);waitNoVpn();fake.close();responder.join(1000);
        DnsVpnService.testServers=null;DnsVpnService.testUpstream=null;NetworkProfiles.testKind=null;DnsSettings.fullVpn(ctx,false);ProxyRuntime.testProbeUrl=null;ProxyRuntime.testDnsServer=null;ProxyRuntime.testCertificate=null;CoreProcess.testCapture=false;device.unfreezeRotation();ins.runOnMainSync(()->{Set<Activity> all=new HashSet<>();for(Stage stage:new Stage[]{Stage.RESUMED,Stage.PAUSED,Stage.STOPPED})all.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));for(Activity a:all)a.finish();});
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
        assertNotNull(vpn);InetAddress[] systemAnswer=vpn.getAllByName("example.com");assertTrue(systemAnswer.length>0);
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
        click("profile");screenshot("05-providers-menu");Thread.sleep(300);UiObject2 ai=device.wait(Until.findObject(By.text("DNS-AI · dns-ai.ru")),3000);assertNotNull(ai);assertTrue(ai.clickAndWait(Until.newWindow(),3000));device.waitForIdle();
        assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);assertEquals(DnsProfiles.Transport.DOT,DnsSettings.load(ctx).transport);
        click("transport");assertFalse(device.hasObject(By.text("UDP · без шифрования")));device.pressBack();
        device.setOrientationLeft();Thread.sleep(500);device.setOrientationNatural();Thread.sleep(500);
        assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);
        screenshot("05-dns-ai-selected");
        DnsVpnService.testServers=null;
        try(TlsFixture tls=new TlsFixture(ins.getContext().getAssets().open("dns-test.p12"),false)){
            DnsUpstream.Protector protector=new DnsUpstream.Protector(){public boolean udp(DatagramSocket s){return DnsVpnService.instance!=null && DnsVpnService.instance.protect(s);}public boolean tcp(Socket s){return DnsVpnService.instance!=null && DnsVpnService.instance.protect(s);}};
            DnsVpnService.testUpstream=new DnsUpstream(protector,new DnsProfiles.Selection(tls.profile("localhost"),DnsProfiles.Transport.DOT),new String[]{"127.0.0.1"},tls.server.getLocalPort(),tls.client(),tls.context.getSocketFactory());
            click("power");grant();waitState(DnsVpnService.State.ON);assertEquals("dns_ai",DnsVpnService.current().choice.profile.id);
            UiObject2 picker=device.findObject(By.res(PKG,"profile"));assertNotNull(picker);assertFalse(picker.isEnabled());
            assertTrue(DnsVpnService.current().server.contains("TLS"));assertEquals(DnsProfiles.Transport.DOT,DnsVpnService.current().choice.transport);screenshot("06-dns-ai-tls-active");
            try(DatagramSocket client=new DatagramSocket()){client.setSoTimeout(4000);byte[] q=DnsPackets.probe(151);client.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));byte[] b=new byte[4096];DatagramPacket p=new DatagramPacket(b,b.length);client.receive(p);assertEquals(8,b[p.getLength()-1]);}
            assertTrue(tls.requests.get()>0);click("force_stop");waitState(DnsVpnService.State.OFF);waitNoVpn();
        }finally{DnsVpnService.testUpstream=null;}
        click("profile");Thread.sleep(300);UiObject2 cf=device.wait(Until.findObject(By.text("Cloudflare 1.1.1.1")),3000);assertNotNull(cf);assertTrue(cf.clickAndWait(Until.newWindow(),3000));device.waitForIdle();assertEquals("cloudflare",DnsSettings.load(ctx).profile.id);
        DnsVpnService.testServers=new String[]{"127.0.0.1"};click("power");grant();waitState(DnsVpnService.State.ON);assertEquals("cloudflare",DnsVpnService.current().choice.profile.id);click("power");waitState(DnsVpnService.State.OFF);waitNoVpn();
    }
    @Test public void defaultModesAndLegacyPreferences()throws Exception{
        // Simulate preferences written by 2.1.0 and verify the one-time migration.
        ctx.getSharedPreferences("dns_profiles",Context.MODE_PRIVATE).edit().clear().putString("profile","dns_ai").putString("transport_dns_ai","DOH").commit();
        assertEquals(DnsProfiles.Transport.DOT,DnsSettings.load(ctx).transport);
        DnsSettings.save(ctx,new DnsProfiles.Selection(DnsProfiles.find("dns_ai"),DnsProfiles.Transport.DOH));
        assertEquals(DnsProfiles.Transport.DOH,DnsSettings.load(ctx).transport); // manual override survives load
        for(String id:new String[]{"cloudflare","adguard","quad9"}){
            DnsSettings.save(ctx,new DnsProfiles.Selection(DnsProfiles.find(id),DnsProfiles.Transport.DOT));
            assertEquals(DnsProfiles.Transport.DOH,DnsSettings.select(ctx,id).transport);
            assertEquals(DnsProfiles.Transport.DOT,DnsSettings.select(ctx,"dns_ai").transport);
        }
        assertEquals(DnsProfiles.Transport.UDP,DnsSettings.select(ctx,"geohide").transport);
        // Exercise the same reset through the actual selector UI.
        ctx.startActivity(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();
        click("profile");Thread.sleep(300);UiObject2 ai=device.wait(Until.findObject(By.text("DNS-AI · dns-ai.ru")),3000);assertNotNull(ai);assertTrue(ai.clickAndWait(Until.newWindow(),3000));device.waitForIdle();
        assertEquals(DnsProfiles.Transport.DOT,DnsSettings.load(ctx).transport);
        click("transport");Thread.sleep(300);UiObject2 https=device.wait(Until.findObject(By.text("HTTPS · шифрование")),3000);assertNotNull(https);assertTrue(https.clickAndWait(Until.newWindow(),3000));device.waitForIdle();assertEquals(DnsProfiles.Transport.DOH,DnsSettings.load(ctx).transport);
        click("profile");Thread.sleep(300);UiObject2 cf=device.wait(Until.findObject(By.text("Cloudflare 1.1.1.1")),3000);assertNotNull(cf);assertTrue(cf.clickAndWait(Until.newWindow(),3000));device.waitForIdle();assertEquals(DnsProfiles.Transport.DOH,DnsSettings.load(ctx).transport);
        click("profile");Thread.sleep(300);ai=device.wait(Until.findObject(By.text("DNS-AI · dns-ai.ru")),3000);assertNotNull(ai);assertTrue(ai.clickAndWait(Until.newWindow(),3000));device.waitForIdle();assertEquals(DnsProfiles.Transport.DOT,DnsSettings.load(ctx).transport);
    }
    @Test public void separateNetworkProfilesApplyDuringVpn()throws Exception{
        NetworkProfiles.testKind="wifi";
        ctx.getSharedPreferences("dns_profiles",0).edit().clear().putString("profile","dns_ai").putString("transport_dns_ai","DOT").commit();
        assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);
        NetworkProfiles.testKind="mobile";DnsSettings.select(ctx,"cloudflare");
        NetworkProfiles.testKind="wifi";assertEquals("dns_ai",DnsSettings.load(ctx).profile.id);
        DnsSettings.saveFor(ctx,"wifi",new DnsProfiles.Selection(DnsProfiles.find("dns_ai"),DnsProfiles.Transport.AUTO));
        DnsSettings.saveFor(ctx,"mobile",new DnsProfiles.Selection(DnsProfiles.find("cloudflare"),DnsProfiles.Transport.DOH));
        assertEquals("dns_ai",DnsSettings.loadFor(ctx,"wifi").profile.id);assertEquals("cloudflare",DnsSettings.loadFor(ctx,"mobile").profile.id);
        click("power");grant();waitState(DnsVpnService.State.ON);assertEquals("dns_ai",DnsVpnService.current().choice.profile.id);
        NetworkProfiles.testKind="mobile";DnsVpnService.instance.testNetworkChanged();long end=System.currentTimeMillis()+5000;
        while(!"cloudflare".equals(DnsVpnService.current().choice.profile.id) && System.currentTimeMillis()<end)Thread.sleep(50);
        assertEquals("cloudflare",DnsVpnService.current().choice.profile.id);waitState(DnsVpnService.State.ON);click("power");waitState(DnsVpnService.State.OFF);waitNoVpn();
    }
    @Test public void autoThroughTunAndReportExport()throws Exception{
        DnsSettings.save(ctx,new DnsProfiles.Selection(DnsProfiles.find("dns_ai"),DnsProfiles.Transport.AUTO));DnsVpnService.testServers=null;
        try(TlsFixture tls=new TlsFixture(ins.getContext().getAssets().open("dns-test.p12"),false)){
            DnsVpnService.testUpstream=new DnsUpstream(NetworkProfiles.protector(ctx),new DnsProfiles.Selection(tls.profile("localhost"),DnsProfiles.Transport.DOT),new String[]{"127.0.0.1"},tls.server.getLocalPort(),tls.client(),tls.context.getSocketFactory());
            click("power");grant();waitState(DnsVpnService.State.ON);assertEquals(DnsProfiles.Transport.AUTO,DnsVpnService.current().choice.transport);assertTrue(DnsVpnService.current().server.contains("TLS"));waitDnsRouting();
            try(DatagramSocket socket=new DatagramSocket()){socket.setSoTimeout(6000);byte[] q=DnsPackets.probe(700);socket.send(new DatagramPacket(q,q.length,InetAddress.getByName("10.77.77.1"),53));byte[] b=new byte[1024];DatagramPacket reply=new DatagramPacket(b,b.length);try{socket.receive(reply);}catch(SocketTimeoutException e){throw new AssertionError("TLS fixture requests="+tls.requests.get()+"; created="+DnsVpnService.testUpstream.tlsCreated.get()+"; reused="+DnsVpnService.testUpstream.tlsReused.get()+"\n"+Diagnostics.report(ctx),e);}assertEquals(8,b[reply.getLength()-1]);}
            assertTrue(DnsVpnService.testUpstream.tlsReused.get()>0);screenshot("07-auto-active");click("force_stop");waitState(DnsVpnService.State.OFF);waitNoVpn();
        }finally{DnsVpnService.testUpstream=null;}
        EventLog.record("ss://do-not-export-secret","secret-mode",EventLog.Result.IO,1);
        android.content.ContentValues values=new android.content.ContentValues();values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"GeoHide-QA-Diagnostics.txt");values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain");values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/GeoHide-QA");android.net.Uri file=ctx.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);assertNotNull(file);
        IntentFilter documentFilter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);documentFilter.addDataType("text/plain");
        Instrumentation.ActivityMonitor monitor=ins.addMonitor(documentFilter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(file)),true);
        try{click("export_report");Thread.sleep(500);assertTrue(monitor.getHits()>0);try(InputStream in=ctx.getContentResolver().openInputStream(file)){assertNotNull(in);String report=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(report.contains("GeoHide Switch"));assertTrue(report.contains("AUTO"));assertFalse(report.contains("do-not-export-secret"));}}
        finally{ins.removeMonitor(monitor);ctx.getContentResolver().delete(file,null,null);}
    }
    @Test public void fullVpnNativeShadowsocksTcpUdpAndCleanup()throws Exception{nativeProxy("shadowsocks");}
    @Test public void fullVpnNativeTrojanTlsTcpUdpAndCleanup()throws Exception{nativeProxy("trojan");}
    @Test public void fullVpnNativeVlessTlsTcpUdpAndCleanup()throws Exception{nativeProxy("vless");}
    private void nativeProxy(String type)throws Exception{
        CoreProcess.clearTestLog();CoreProcess.testCapture=true;
        DnsVpnService.testServers=null;
        try(ServerSocket http=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));ServerSocket reserve=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            int remotePort=reserve.getLocalPort();reserve.close();java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger();
            Thread responder=new Thread(()->{while(!http.isClosed())try(Socket socket=http.accept()){socket.setSoTimeout(4000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line;while((line=input.readLine())!=null && !line.isEmpty()){}byte[] body="TUN-SS-OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);OutputStream output=socket.getOutputStream();output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.write(body);output.flush();hits.incrementAndGet();}catch(Exception ignored){}},"Remote-HTTP-fixture");responder.start();
            String uuid=java.util.UUID.randomUUID().toString(),certificate=null;
            org.json.JSONObject inbound=new org.json.JSONObject().put("type",type).put("listen","127.0.0.1").put("listen_port",remotePort);
            String link;
            if("shadowsocks".equals(type)){inbound.put("method","aes-128-gcm").put("password","fixture-secret");String credentials=java.util.Base64.getEncoder().encodeToString("aes-128-gcm:fixture-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));link="ss://"+credentials+"@127.0.0.1:"+remotePort;}
            else{
                java.security.KeyStore keys=java.security.KeyStore.getInstance("PKCS12");try(InputStream stream=ins.getContext().getAssets().open("dns-test.p12")){keys.load(stream,"dns-test-only".toCharArray());}String alias=keys.aliases().nextElement();
                java.util.Base64.Encoder encoder=java.util.Base64.getMimeEncoder(64,new byte[]{'\n'});certificate="-----BEGIN CERTIFICATE-----\n"+encoder.encodeToString(keys.getCertificate(alias).getEncoded())+"\n-----END CERTIFICATE-----\n";String privateKey="-----BEGIN PRIVATE KEY-----\n"+encoder.encodeToString(keys.getKey(alias,"dns-test-only".toCharArray()).getEncoded())+"\n-----END PRIVATE KEY-----\n";
                inbound.put("tls",new org.json.JSONObject().put("enabled",true).put("certificate",new org.json.JSONArray().put(certificate)).put("key",new org.json.JSONArray().put(privateKey)));
                org.json.JSONObject user=new org.json.JSONObject().put("name","fixture").put("vless".equals(type)?"uuid":"password","vless".equals(type)?uuid:"fixture-secret");inbound.put("users",new org.json.JSONArray().put(user));link=type+"://"+("vless".equals(type)?uuid:"fixture-secret")+"@127.0.0.1:"+remotePort+"?security=tls&sni=localhost&fp=chrome";
            }
            org.json.JSONObject rule=new org.json.JSONObject().put("ip_cidr",new org.json.JSONArray().put("198.18.77.1/32").put("fd77:77::77/128")).put("action","route-options").put("override_address","127.0.0.1").put("override_port",http.getLocalPort());
            org.json.JSONObject server=new org.json.JSONObject().put("log",new org.json.JSONObject().put("disabled",true)).put("inbounds",new org.json.JSONArray().put(inbound)).put("outbounds",new org.json.JSONArray().put(new org.json.JSONObject().put("type","direct").put("tag","direct"))).put("route",new org.json.JSONObject().put("rules",new org.json.JSONArray().put(rule)).put("final","direct"));
            try(CoreProcess remote=CoreProcess.start(ctx,server)){
                if("vless".equals(type)){
                    byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);String pub=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);org.json.JSONObject reality=ProxyConfig.parse("vless://"+uuid+"@127.0.0.1:443?security=reality&sni=localhost&pbk="+pub+"&sid=abcd&fp=chrome");
                    try(ServerSocket lease=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){int unused=lease.getLocalPort();lease.close();try(CoreProcess checked=CoreProcess.start(ctx,ProxyConfig.client(reality,unused,"test","test"))){assertTrue(checked.process.isAlive());}}
                }
                Thread.sleep(500);ProxyStore.save(ctx,link);ProxyRuntime.testCertificate=certificate;assertFalse(ctx.getSharedPreferences("proxy",0).getString("sealed","").contains("fixture-secret"));
                ProxyRuntime.testProbeUrl="http://198.18.77.1:"+http.getLocalPort()+"/";ProxyRuntime.testDnsServer=new org.json.JSONObject().put("type","udp").put("tag","test-dns").put("server","127.0.0.1").put("server_port",fake.getLocalPort()).put("detour","proxy");DnsSettings.fullVpn(ctx,true);
                ctx.startActivity(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();click("power");grant();waitState(DnsVpnService.State.ON);assertTrue(DnsVpnService.current().full);assertNotNull(DnsVpnService.proxy());assertTrue(DnsVpnService.proxy().alive());screenshot("08-full-vpn-"+type+"-active");
                ctx.startActivity(new Intent().setClassName("dev.geohide.probe","dev.geohide.probe.ProbeActivity").putExtra("url",ProxyRuntime.testProbeUrl).putExtra("url6","http://[fd77:77::77]:"+http.getLocalPort()+"/").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));assertTrue(device.wait(Until.hasObject(By.text("VPN PROBE OK: TCPv4 + TCPv6 + UDP DNS")),16000));
                assertTrue("HTTP fixture hits="+hits.get(),hits.get()>=3);long[] stats=DnsVpnService.proxy().stats();assertTrue("TX="+Arrays.toString(stats),stats[1]>0);assertTrue("RX="+Arrays.toString(stats),stats[3]>0);
                ctx.startActivity(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();ProxyRuntime saved=DnsVpnService.proxy();long began=System.currentTimeMillis();tap(R.id.force_stop);waitState(DnsVpnService.State.OFF);waitNoVpn();assertFalse(saved.alive());assertTrue(System.currentTimeMillis()-began<4000);assertFalse(hasOngoingNotification());assertFalse(Diagnostics.report(ctx).contains("fixture-secret"));ProxyStore.clear(ctx);
            }finally{http.close();responder.join(1000);}
        }
    }
    private void waitDnsRouting()throws Exception{ConnectivityManager cm=ctx.getSystemService(ConnectivityManager.class);long end=System.currentTimeMillis()+4000;while(System.currentTimeMillis()<end){Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return;Thread.sleep(50);}fail("VPN route did not become active");}
    private void waitNoVpn()throws Exception{long end=System.currentTimeMillis()+3000;while(vpnPresent() && System.currentTimeMillis()<end)Thread.sleep(50);assertFalse(vpnPresent());}
    private boolean vpnPresent(){ConnectivityManager cm=ctx.getSystemService(ConnectivityManager.class);for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return true;}return false;}
    private boolean hasOngoingNotification(){for(android.service.notification.StatusBarNotification n:ctx.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==1203)return true;return false;}
}
