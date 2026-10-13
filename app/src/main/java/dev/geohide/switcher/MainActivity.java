package dev.geohide.switcher;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int BG=Color.rgb(11,17,19),LIME=Color.rgb(207,251,123),MUTED=Color.rgb(145,162,168);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PowerButton power;
    private TextView status,detail,stats,profileInfo;
    private Button profileButton,transportButton,modeButton,networkButton;
    private Diagnostics diagnostic;
    private final java.util.concurrent.ExecutorService checks=java.util.concurrent.Executors.newSingleThreadExecutor();
    private String pendingReport;

    private DnsProfiles.Selection selection;
    private boolean permissionPending,startingRequested;
    private final Runnable update=new Runnable(){public void run(){refresh();handler.postDelayed(this,250);}};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private void add(LinearLayout root,View v,int h,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,h);p.topMargin=dp(top);root.addView(v,p);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);pendingReport=saved==null?null:saved.getString("pendingReport");selection=DnsSettings.load(this);permissionPending=saved!=null && saved.getBoolean("permissionPending");
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),dp(24)+insets.getSystemWindowInsetTop(),dp(24),dp(24)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        TextView title=text("GeoHide Switch",32,Color.WHITE);title.setTypeface(null,Typeface.BOLD);add(root,title,-2,4);
        add(root,text("DNS ПОД ВАШИМ КОНТРОЛЕМ",12,MUTED),-2,4);
        modeButton=roundedButton("",Color.WHITE,Color.rgb(30,40,43));modeButton.setId(R.id.mode);add(root,modeButton,dp(44),16);modeButton.setOnClickListener(v->chooseMode());
        networkButton=roundedButton("",MUTED,BG);networkButton.setId(R.id.network_profiles);add(root,networkButton,dp(40),4);networkButton.setOnClickListener(v->networkProfiles());
        profileButton=roundedButton("",LIME,BG);profileButton.setId(R.id.profile);
        add(root,profileButton,dp(50),8);profileButton.setOnClickListener(v->{if(DnsSettings.fullVpn(this))importProxy();else chooseProfile();});
        profileInfo=text("",13,MUTED);profileInfo.setId(R.id.profile_info);add(root,profileInfo,-2,8);
        transportButton=roundedButton("",Color.WHITE,Color.rgb(30,40,43));transportButton.setId(R.id.transport);
        add(root,transportButton,dp(44),10);transportButton.setOnClickListener(v->chooseTransport());
        power=new PowerButton();power.setId(R.id.power);power.setOnClickListener(v->toggle());
        int width=getResources().getDisplayMetrics().widthPixels-dp(48);int diameter=Math.min(dp(180),Math.min(width,(int)(getResources().getDisplayMetrics().heightPixels*.24f)));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(diameter,diameter);pp.topMargin=dp(20);root.addView(power,pp);
        status=text("ВЫКЛЮЧЕНО",23,Color.WHITE);status.setId(R.id.status);add(root,status,-2,14);
        detail=text("",15,MUTED);detail.setId(R.id.detail);add(root,detail,-2,12);
        stats=text("",12,MUTED);add(root,stats,-2,16);
        Button check=roundedButton("Проверить сервис",Color.WHITE,Color.rgb(30,40,43));check.setId(R.id.diagnostics);add(root,check,dp(46),14);check.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Что проверить?").setItems(Diagnostics.NAMES,(dialog,which)->checkService(which)).setNegativeButton("Отмена",null).show());
        Button report=roundedButton("Сохранить отчёт диагностики",MUTED,BG);report.setId(R.id.export_report);add(root,report,dp(44),6);report.setOnClickListener(v->exportReport());
        Button force=new Button(this);force.setId(R.id.force_stop);force.setText("Принудительно отключить");force.setAllCaps(false);force.setTextSize(15);force.setTextColor(Color.WHITE);
        GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(30,40,43));d.setCornerRadius(dp(26));force.setBackground(d);
        add(root,force,dp(52),16);force.setOnClickListener(v->stop());
        Button settings=new Button(this);settings.setText("Открыть настройки VPN");settings.setAllCaps(false);settings.setTextColor(MUTED);settings.setTextSize(14);settings.setBackgroundColor(Color.TRANSPARENT);
        add(root,settings,dp(48),8);settings.setOnClickListener(v->{try{startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS));}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}});
        add(root,text("Без root · DNS либо полный VPN\nДругой VPN одновременно использовать нельзя.\nСобственный Secure DNS браузера может обходить приложение.",12,MUTED),-2,22);
        add(root,text("GeoHide Switch 2.2 · DNS и VPN\nDNS не заменяет полноценный обход DPI.",11,MUTED),-2,18);
        setContentView(scroll);refresh();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},23);
    }
    @Override protected void onResume(){super.onResume();selection=DnsSettings.load(this);handler.removeCallbacks(update);update.run();}
    @Override protected void onPause(){handler.removeCallbacks(update);if(diagnostic!=null){diagnostic.cancel();diagnostic=null;}super.onPause();}
    @Override protected void onDestroy(){if(diagnostic!=null)diagnostic.cancel();checks.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("permissionPending",permissionPending);b.putString("pendingReport",pendingReport);super.onSaveInstanceState(b);}
    private void refresh(){
        if(power==null)return;DnsVpnService.Snapshot s=DnsVpnService.current();
        if(s.state!=DnsVpnService.State.OFF)startingRequested=false;
        boolean connected=s.state==DnsVpnService.State.ON;
        boolean busy=startingRequested || s.state==DnsVpnService.State.STARTING;
        boolean enabled=connected || busy || s.state==DnsVpnService.State.WAITING;
        power.on=enabled;power.accent=connected;power.label=enabled?"Выключить":"Включить";
        if(permissionPending)power.label="Ожидаем разрешение";
        power.setContentDescription(power.label+" подключение");power.invalidate();
        if(!enabled && !permissionPending)selection=DnsSettings.load(this);
        boolean full=enabled?s.full:DnsSettings.fullVpn(this);
        modeButton.setText(full?"Режим: полный VPN":"Режим: только DNS");modeButton.setEnabled(!enabled && !permissionPending);
        networkButton.setText(NetworkProfiles.label(NetworkProfiles.kind(this))+" · профили сети");networkButton.setEnabled(!enabled && !permissionPending);
        DnsProfiles.Selection shown=enabled && s.choice!=null?s.choice:selection;
        profileButton.setText(full?"VPN-сервер · импортировать":shown.profile.name+" · Выбрать сервер");
        profileInfo.setText(full?(ProxyStore.exists(this)?"Конфигурация сохранена · SS / VLESS / Trojan":"Нужен рабочий сервер · SS / VLESS / Trojan")+"\nDNS через туннель · приложение использует прокси для проверок":shown.profile.description);if(!full && NetworkProfiles.strictPrivateDns(this))profileInfo.append("\nЗадан системный Private DNS: проверьте его при расхождении результатов.");transportButton.setVisibility(full?View.GONE:View.VISIBLE);transportButton.setText("Подключение: "+shown.transport.label);
        boolean editable=!enabled && !permissionPending;profileButton.setEnabled(editable);transportButton.setEnabled(editable);
        profileButton.setAlpha(editable?1f:.55f);transportButton.setAlpha(editable?1f:.55f);
        String label;
        switch(s.state){case ON:label=full?"VPN ВКЛЮЧЁН":"DNS ВКЛЮЧЁН";break;case STARTING:label="ПОДКЛЮЧЕНИЕ…";break;case WAITING:label=full?"НЕТ СЕТИ":"НЕТ ОТВЕТА DNS";break;case ERROR:label="ОШИБКА";break;default:label=full?"VPN ВЫКЛЮЧЕН":"DNS ВЫКЛЮЧЕН";}
        status.setText(permissionPending?"РАЗРЕШЕНИЕ VPN…":startingRequested?"ПОДКЛЮЧЕНИЕ…":label);
        status.setTextColor(connected?LIME:s.state==DnsVpnService.State.ERROR || s.state==DnsVpnService.State.WAITING?Color.rgb(255,193,122):Color.WHITE);
        detail.setText(s.detail);
        if(full){ProxyRuntime runtime=DnsVpnService.proxy();long[] traffic=runtime!=null && runtime.alive()?runtime.stats():new long[4];stats.setText("VPN · "+(runtime==null?"не подключён":runtime.type)+"\nПередано: "+traffic[1]+" Б · Принято: "+traffic[3]+" Б");}
        else stats.setText((s.server.isEmpty()?shown.label():"Сервер: "+s.server)+"\nОтветов: "+s.answered+" · Ошибок: "+s.failed);
    }
    private Button roundedButton(String label,int foreground,int background){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(foreground);
        GradientDrawable d=new GradientDrawable();d.setColor(background);d.setCornerRadius(dp(24));b.setBackground(d);return b;
    }
    private void chooseProfile(){
        String[] names=new String[DnsProfiles.ALL.length];int checked=0;
        for(int i=0;i<names.length;i++){names[i]=DnsProfiles.ALL[i].name;if(DnsProfiles.ALL[i].id.equals(selection.profile.id))checked=i;}
        new AlertDialog.Builder(this).setTitle("DNS-сервер").setSingleChoiceItems(names,checked,(dialog,which)->{
            selection=DnsSettings.select(this,DnsProfiles.ALL[which].id);dialog.dismiss();refresh();
        }).setNegativeButton("Отмена",null).show();
    }
    private void chooseTransport(){
        DnsProfiles.Transport[] modes=selection.profile.transports();String[] labels=new String[modes.length];int checked=0;
        for(int i=0;i<modes.length;i++){labels[i]=modes[i].label;if(modes[i]==selection.transport)checked=i;}
        new AlertDialog.Builder(this).setTitle("Способ подключения").setSingleChoiceItems(labels,checked,(dialog,which)->{
            selection=new DnsProfiles.Selection(selection.profile,modes[which]);DnsSettings.save(this,selection);dialog.dismiss();refresh();
        }).setNegativeButton("Отмена",null).show();
    }
    private void toggle(){
        if(permissionPending)return;
        DnsVpnService.State s=DnsVpnService.current().state;
        if(startingRequested || s==DnsVpnService.State.ON || s==DnsVpnService.State.STARTING || s==DnsVpnService.State.WAITING){stop();return;}
        try{Intent prepare=VpnService.prepare(this);if(prepare!=null){permissionPending=true;refresh();startActivityForResult(prepare,22);}else connect();}
        catch(Exception e){showError(e);}
    }
    private void connect(){
        selection=DnsSettings.load(this);DnsVpnService.clearError();startingRequested=true;refresh();
        try{startForegroundService(new Intent(this,DnsVpnService.class).setAction("START"));}
        catch(Exception e){startingRequested=false;showError(e);}handler.postDelayed(this::refresh,80);
    }
    private void stop(){if(diagnostic!=null){diagnostic.cancel();diagnostic=null;}permissionPending=false;startingRequested=false;try{startService(new Intent(this,DnsVpnService.class).setAction("STOP"));}catch(Exception e){showError(e);}DnsVpnService.clearError();handler.postDelayed(this::refresh,80);refresh();}
    private void showError(Exception e){new AlertDialog.Builder(this).setTitle("Не удалось включить DNS").setMessage(e.getMessage()).setPositiveButton("Понятно",null).show();}
    @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);if(rc==24){String report=pendingReport;pendingReport=null;if(result==RESULT_OK && data!=null && data.getData()!=null && report!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new java.io.IOException();out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"Отчёт сохранён",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Не удалось сохранить отчёт",Toast.LENGTH_LONG).show();}}return;}if(rc==22){boolean requested=permissionPending;permissionPending=false;if(requested && result==RESULT_OK)connect();refresh();}}
    private void chooseMode(){new AlertDialog.Builder(this).setTitle("Режим подключения").setSingleChoiceItems(new String[]{"Только DNS · GeoHide / DNS-AI / другие","Полный VPN · собственный сервер"},DnsSettings.fullVpn(this)?1:0,(dialog,which)->{DnsSettings.fullVpn(this,which==1);dialog.dismiss();refresh();}).setNegativeButton("Отмена",null).show();}
    private void importProxy(){
        EditText input=new EditText(this);input.setHint("ss://… / vless://… / trojan://…\nили JSON одного outbound sing-box");input.setMinLines(3);input.setMaxLines(6);input.setTextColor(Color.WHITE);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32768)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Импорт VPN-сервера").setMessage("Конфигурация сохраняется зашифрованно. TLS-проверка обязательна; старые и открытые шифры не поддерживаются.").setView(input).setPositiveButton("Сохранить",null).setNeutralButton("Удалить",(d,w)->{ProxyStore.clear(this);refresh();}).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{ProxyStore.save(this,input.getText().toString());input.setText("");dialog.dismiss();refresh();Toast.makeText(this,"Конфигурация сохранена",Toast.LENGTH_SHORT).show();}catch(Exception e){input.setError("Проверьте ссылку, AEAD-шифр, TLS/REALITY и формат одного outbound");}}));dialog.show();
    }
    private void networkProfiles(){
        String[] kinds={"wifi","mobile","other"};String[] items=new String[3];for(int i=0;i<3;i++)items[i]=NetworkProfiles.label(kinds[i])+": "+DnsSettings.loadFor(this,kinds[i]).label();
        new AlertDialog.Builder(this).setTitle("Профили DNS по типу сети").setItems(items,(d,index)->{
            String kind=kinds[index];String[] names=new String[DnsProfiles.ALL.length];for(int i=0;i<names.length;i++)names[i]=DnsProfiles.ALL[i].name;
            new AlertDialog.Builder(this).setTitle(NetworkProfiles.label(kind)+" · DNS").setItems(names,(pd,which)->{
                DnsProfiles.Profile profile=DnsProfiles.ALL[which];DnsProfiles.Transport[] modes=profile.transports();String[] labels=new String[modes.length];for(int i=0;i<labels.length;i++)labels[i]=modes[i].label;
                new AlertDialog.Builder(this).setTitle(profile.name+" · режим").setItems(labels,(md,mode)->{DnsSettings.saveFor(this,kind,new DnsProfiles.Selection(profile,modes[mode]));selection=DnsSettings.load(this);refresh();}).setNegativeButton("Отмена",null).show();
            }).setNegativeButton("Отмена",null).show();
        }).setNegativeButton("Закрыть",null).show();
    }
    private void checkService(int index){
        if(diagnostic!=null)diagnostic.cancel();Diagnostics task=new Diagnostics(this);diagnostic=task;
        TextView result=text("Проверяем "+Diagnostics.NAMES[index]+"…\nDNS → TCP → TLS → HTTP. Вход в аккаунт не проверяется.",14,Color.WHITE);result.setPadding(dp(18),dp(14),dp(18),dp(14));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Проверка сервиса").setView(result).setPositiveButton("Закрыть",null).setNeutralButton("Сохранить отчёт",(d,w)->exportReport()).create();dialog.setOnDismissListener(d->{if(diagnostic==task){task.cancel();diagnostic=null;}});dialog.show();
        checks.execute(()->{String text=task.run(index);handler.post(()->{if(diagnostic==task && !isFinishing())result.setText(text);});});
    }
    private void exportReport(){pendingReport=Diagnostics.report(this);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"GeoHideSwitch-Diagnostics-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new java.util.Date())+".txt");try{startActivityForResult(intent,24);}catch(Exception e){pendingReport=null;Toast.makeText(this,"На устройстве недоступен выбор файла",Toast.LENGTH_LONG).show();}}
    private class PowerButton extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);boolean on,accent;String label="Включить";
        PowerButton(){super(MainActivity.this);setClickable(true);setFocusable(true);setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});}
        @Override public boolean performClick(){return super.performClick();}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),cx=w/2,cy=h*.43f,r=w*.17f;
            p.setStyle(Paint.Style.FILL);p.setColor(on?Color.rgb(29,54,42):LIME);if(isPressed())p.setAlpha(180);c.drawCircle(w/2,h/2,Math.min(w,h)/2-2,p);p.setAlpha(255);
            p.setColor(on?LIME:BG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.025f);p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-50,280,false,p);c.drawLine(cx,cy-r*1.28f,cx,cy-r*.1f,p);
            p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(18*getResources().getDisplayMetrics().scaledDensity);if(p.measureText(label)>w*.82f)p.setTextSize(p.getTextSize()*w*.82f/p.measureText(label));p.setTextAlign(Paint.Align.CENTER);c.drawText(label,cx,h*.77f,p);
        }
    }
}
