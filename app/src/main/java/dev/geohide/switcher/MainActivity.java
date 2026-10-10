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
    private TextView status,detail,stats;
    private boolean permissionPending,startingRequested;
    private final Runnable update=new Runnable(){public void run(){refresh();handler.postDelayed(this,250);}};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private void add(LinearLayout root,View v,int h,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,h);p.topMargin=dp(top);root.addView(v,p);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);permissionPending=saved!=null && saved.getBoolean("permissionPending");
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),dp(24)+insets.getSystemWindowInsetTop(),dp(24),dp(24)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        TextView title=text("GeoHide",36,Color.WHITE);title.setTypeface(null,Typeface.BOLD);add(root,title,-2,4);
        add(root,text("DNS ПОД ВАШИМ КОНТРОЛЕМ",12,MUTED),-2,4);
        power=new PowerButton();power.setId(R.id.power);power.setOnClickListener(v->toggle());
        int width=getResources().getDisplayMetrics().widthPixels-dp(48);int diameter=Math.min(dp(248),width);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(diameter,diameter);pp.topMargin=dp(32);root.addView(power,pp);
        status=text("ВЫКЛЮЧЕНО",23,Color.WHITE);status.setId(R.id.status);add(root,status,-2,22);
        detail=text("",15,MUTED);detail.setId(R.id.detail);add(root,detail,-2,12);
        stats=text("",12,MUTED);add(root,stats,-2,16);
        Button force=new Button(this);force.setId(R.id.force_stop);force.setText("Принудительно отключить");force.setAllCaps(false);force.setTextSize(15);force.setTextColor(Color.WHITE);
        GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(30,40,43));d.setCornerRadius(dp(26));force.setBackground(d);
        add(root,force,dp(52),24);force.setOnClickListener(v->stop());
        Button settings=new Button(this);settings.setText("Открыть настройки VPN");settings.setAllCaps(false);settings.setTextColor(MUTED);settings.setTextSize(14);settings.setBackgroundColor(Color.TRANSPARENT);
        add(root,settings,dp(48),8);settings.setOnClickListener(v->{try{startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS));}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}});
        add(root,text("Без root · локальный VPN только для DNS\nДругой VPN одновременно использовать нельзя.\nСобственный Secure DNS браузера может обходить приложение.",12,MUTED),-2,22);
        add(root,text("GeoHide Switch 2.0 · неофициальный клиент\nGeoHide не является полноценным обходом DPI.",11,MUTED),-2,18);
        setContentView(scroll);refresh();
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(update);update.run();}
    @Override protected void onPause(){handler.removeCallbacks(update);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("permissionPending",permissionPending);super.onSaveInstanceState(b);}
    private void refresh(){
        if(power==null)return;DnsVpnService.Snapshot s=DnsVpnService.current();
        if(s.state!=DnsVpnService.State.OFF)startingRequested=false;
        boolean connected=s.state==DnsVpnService.State.ON;
        boolean busy=startingRequested || s.state==DnsVpnService.State.STARTING;
        boolean enabled=connected || busy || s.state==DnsVpnService.State.WAITING;
        power.on=enabled;power.accent=connected;power.label=enabled?"Выключить":"Включить";
        if(permissionPending)power.label="Ожидаем разрешение";
        power.setContentDescription(power.label+" GeoHide DNS");power.invalidate();
        String label;
        switch(s.state){case ON:label="DNS ВКЛЮЧЁН";break;case STARTING:label="ПОДКЛЮЧЕНИЕ…";break;case WAITING:label="НЕТ ОТВЕТА DNS";break;case ERROR:label="ОШИБКА";break;default:label="DNS ВЫКЛЮЧЕН";}
        status.setText(permissionPending?"РАЗРЕШЕНИЕ VPN…":startingRequested?"ПОДКЛЮЧЕНИЕ…":label);
        status.setTextColor(connected?LIME:s.state==DnsVpnService.State.ERROR || s.state==DnsVpnService.State.WAITING?Color.rgb(255,193,122):Color.WHITE);
        detail.setText(s.detail);
        stats.setText((s.server.isEmpty()?"GeoHide DNS · UDP / 53":"Сервер: "+s.server)+"\nОтветов: "+s.answered+" · Ошибок: "+s.failed);
    }
    private void toggle(){
        if(permissionPending)return;
        DnsVpnService.State s=DnsVpnService.current().state;
        if(startingRequested || s==DnsVpnService.State.ON || s==DnsVpnService.State.STARTING || s==DnsVpnService.State.WAITING){stop();return;}
        try{Intent prepare=VpnService.prepare(this);if(prepare!=null){permissionPending=true;refresh();startActivityForResult(prepare,22);}else connect();}
        catch(Exception e){showError(e);}
    }
    private void connect(){
        startingRequested=true;refresh();
        try{startForegroundService(new Intent(this,DnsVpnService.class).setAction("START"));}
        catch(Exception e){startingRequested=false;showError(e);}handler.postDelayed(this::refresh,80);
    }
    private void stop(){permissionPending=false;startingRequested=false;stopService(new Intent(this,DnsVpnService.class));DnsVpnService.clearError();handler.postDelayed(this::refresh,80);refresh();}
    private void showError(Exception e){new AlertDialog.Builder(this).setTitle("Не удалось включить DNS").setMessage(e.getMessage()).setPositiveButton("Понятно",null).show();}
    @Override protected void onActivityResult(int rc,int result,Intent data){super.onActivityResult(rc,result,data);if(rc==22){boolean requested=permissionPending;permissionPending=false;if(requested && result==RESULT_OK)connect();refresh();}}
    private class PowerButton extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);boolean on,accent;String label="Включить";
        PowerButton(){super(MainActivity.this);setClickable(true);setFocusable(true);setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});}
        @Override public boolean performClick(){return super.performClick();}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),cx=w/2,cy=h*.43f,r=w*.17f;
            p.setStyle(Paint.Style.FILL);p.setColor(on?Color.rgb(29,54,42):LIME);if(isPressed())p.setAlpha(180);c.drawCircle(w/2,h/2,Math.min(w,h)/2-2,p);p.setAlpha(255);
            p.setColor(on?LIME:BG);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.025f);p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-50,280,false,p);c.drawLine(cx,cy-r*1.28f,cx,cy-r*.1f,p);
            p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(dp(18));p.setTextAlign(Paint.Align.CENTER);c.drawText(label,cx,h*.77f,p);
        }
    }
}
