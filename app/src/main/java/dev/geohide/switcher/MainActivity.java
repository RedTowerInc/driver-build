package dev.geohide.switcher;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.os.Build;

public class MainActivity extends Activity {
    private final int bg=Color.rgb(11,17,19), lime=Color.rgb(207,251,123), white=Color.WHITE;
    private TextView button, status;
    private LinearLayout root;
    private GradientDrawable bubble(int color, int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    private TextView txt(String s,int sp,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);return t;
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        root=new LinearLayout(this);root.setBackgroundColor(bg);root.setOrientation(1);
        root.setGravity(Gravity.CENTER_HORIZONTAL);root.setPadding(dp(24),dp(40),dp(24),dp(30));
        TextView heading=txt("GeoHide",32,white);heading.setTypeface(null,Typeface.BOLD);
        root.addView(heading);
        TextView subtitle=txt("DNS  /  QUICK CONTROL",12,Color.rgb(142,159,167));
        root.addView(subtitle);
        button=txt("⏻",98,bg);button.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(dp(245),dp(245));
        bl.gravity=Gravity.CENTER_HORIZONTAL;bl.topMargin=dp(55);
        button.setBackground(bubble(lime,dp(140)));
        root.addView(button,bl);button.setOnClickListener(v->toggle());
        status=txt("ВЫКЛЮЧЕНО",22,white);
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,dp(64));
        sl.topMargin=dp(22);root.addView(status,sl);
        TextView help=txt("Одна кнопка для GeoHide DNS. При первом запуске Android попросит разрешение VPN.",15,Color.rgb(142,159,167));
        LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(-1,-2);hl.topMargin=dp(26);
        root.addView(help,hl);
        TextView note=txt("Только DNS · без root · использует VPN-слот Android",12,Color.rgb(140,154,160));
        LinearLayout.LayoutParams nl=new LinearLayout.LayoutParams(-1,-2);nl.topMargin=dp(45);
        root.addView(note,nl);
        setContentView(root);refresh();
    }
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        if(button==null)return;
        boolean active=DnsVpnService.active;
        button.setTextColor(active?lime:bg);
        button.setBackground(bubble(active?Color.rgb(29,54,42):lime,dp(140)));
        status.setText(active ? "DNS ВКЛЮЧЁН":"DNS ВЫКЛЮЧЕН");
    }
    private void toggle(){
        if(DnsVpnService.active){stopService(new Intent(this,DnsVpnService.class));refresh();return;}
        Intent prep=VpnService.prepare(this);
        if(prep!=null){startActivityForResult(prep,22);return;}
        connect();
    }
    private void connect(){Intent i=new Intent(this,DnsVpnService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);button.postDelayed(this::refresh,650);}
    @Override protected void onActivityResult(int rc,int result,Intent data){
        super.onActivityResult(rc,result,data);if(rc==22 && result==RESULT_OK)connect();
    }
}