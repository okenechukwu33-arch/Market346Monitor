package com.market346.monitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int PROJECTION_REQUEST = 5001;
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status;

    private int bg = 0xFF090A0B;
    private int card = 0xFF17191C;
    private int white = 0xFFF5F5F5;
    private int muted = 0xFFB8BCC2;
    private int green = 0xFF22C55E;
    private int red = 0xFFFF5A52;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("346", MODE_PRIVATE);
        showHome();
        requestNotifications();
    }

    private void showHome() {
        root = base("346 Monitor");
        TextView sub = text("Monitor Market 346 on Phone 1 and receive alerts on Phone 2.", 15, muted);
        root.addView(sub);

        TextView info = text("Choose this phone's role", 17, white);
        info.setPadding(0, 32, 0, 16);
        root.addView(info);

        Button monitor = button("PHONE 1  •  MONITOR", green);
        monitor.setOnClickListener(v -> showMonitorSetup());
        root.addView(monitor);

        Button receiver = button("PHONE 2  •  RECEIVER", 0xFF2D3137);
        receiver.setOnClickListener(v -> showReceiver());
        root.addView(receiver);

        TextView note = text("Both phones use the same APK. The monitor watches only the screen area you authorize through Android's screen-capture permission.", 13, muted);
        note.setPadding(0, 28, 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private void showMonitorSetup() {
        root = base("Monitor Setup");
        root.addView(text("Market ID / Name", 13, muted));
        EditText market = field(prefs.getString("market", "346"));
        root.addView(market);

        root.addView(text("Market Name (optional)", 13, muted));
        EditText name = field(prefs.getString("market_name", "Match Odds"));
        root.addView(name);

        root.addView(text("Detection Mode", 13, muted));
        Spinner mode = new Spinner(this);
        String[] modes = {"Auto (Suspended / Odds)", "Text only", "Color + Text"};
        mode.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        root.addView(mode);

        root.addView(text("Select Area to Monitor", 13, muted));
        TextView area = text(areaSummary(), 14, white);
        area.setPadding(16, 16, 16, 16);
        area.setBackgroundColor(card);
        root.addView(area);

        Button select = button("SELECT AREA", green);
        select.setOnClickListener(v -> showAreaDialog(area));
        root.addView(select);

        TextView explain = text("The detector watches only this percentage rectangle of the captured screen.", 12, muted);
        explain.setPadding(0, 8, 0, 16);
        root.addView(explain);

        Button start = button("SAVE & START", green);
        start.setOnClickListener(v -> {
            prefs.edit().putString("market", market.getText().toString().trim()).putString("market_name", name.getText().toString().trim()).apply();
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), PROJECTION_REQUEST);
        });
        root.addView(start);

        Button back = button("BACK", 0xFF2D3137);
        back.setOnClickListener(v -> showHome());
        root.addView(back);
        setContentView(root);
    }

    private String areaSummary() {
        return "Left " + prefs.getInt("left", 10) + "%   Top " + prefs.getInt("top", 20) + "%\nRight " + prefs.getInt("right", 95) + "%   Bottom " + prefs.getInt("bottom", 78) + "%";
    }

    private void showAreaDialog(TextView summary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = 18;
        box.setPadding(pad, pad, pad, pad);
        String[] labels = {"Left %", "Top %", "Right %", "Bottom %"};
        String[] keys = {"left", "top", "right", "bottom"};
        EditText[] fields = new EditText[4];
        for (int i=0;i<4;i++) {
            TextView l = text(labels[i], 13, muted); box.addView(l);
            fields[i] = field(String.valueOf(prefs.getInt(keys[i], new int[]{10,20,95,78}[i])));
            fields[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            box.addView(fields[i]);
        }
        new android.app.AlertDialog.Builder(this).setTitle("Area to monitor").setView(box)
            .setPositiveButton("SAVE", (d,w) -> {
                try {
                    int l=Integer.parseInt(fields[0].getText().toString()); int t=Integer.parseInt(fields[1].getText().toString());
                    int r=Integer.parseInt(fields[2].getText().toString()); int b=Integer.parseInt(fields[3].getText().toString());
                    if(l>=0&&t>=0&&r<=100&&b<=100&&l<r&&t<b) prefs.edit().putInt("left",l).putInt("top",t).putInt("right",r).putInt("bottom",b).apply();
                    summary.setText(areaSummary());
                } catch(Exception ignored) {}
            }).setNegativeButton("CANCEL", null).show();
    }

    private void showReceiver() {
        root = base("Receiver");
        root.addView(text("Monitor phone IP address", 13, muted));
        EditText ip = field(prefs.getString("monitor_ip", "192.168.1.10"));
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(ip);

        TextView p = text("Port: 8765  •  Both phones should be on the same Wi-Fi network for this direct connection.", 12, muted);
        p.setPadding(0, 6, 0, 18);
        root.addView(p);

        status = text("● NOT CONNECTED", 18, red);
        status.setPadding(0, 12, 0, 20);
        root.addView(status);

        Button connect = button("CONNECT & LISTEN", green);
        connect.setOnClickListener(v -> {
            prefs.edit().putString("monitor_ip", ip.getText().toString().trim()).apply();
            Intent i = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RECEIVER);
            i.putExtra("ip", ip.getText().toString().trim());
            startForegroundServiceCompat(i);
            status.setText("● LISTENING FOR 346 CHANGES"); status.setTextColor(green);
        });
        root.addView(connect);

        Button stop = button("STOP RECEIVER", red);
        stop.setOnClickListener(v -> stopService(new Intent(this, MonitorService.class)));
        root.addView(stop);

        Button history = button("ALERT HISTORY", 0xFF2D3137);
        history.setOnClickListener(v -> showHistory());
        root.addView(history);

        Button back = button("BACK", 0xFF2D3137);
        back.setOnClickListener(v -> showHome()); root.addView(back);
        setContentView(root);
    }

    private void showHistory() {
        String h = prefs.getString("history", "No alerts yet.");
        new android.app.AlertDialog.Builder(this).setTitle("Alert History").setMessage(h).setPositiveButton("OK", null).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PROJECTION_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent i = new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_MONITOR);
                i.putExtra("resultCode", resultCode); i.putExtra("data", data);
                startForegroundServiceCompat(i);
                showMonitoring();
            } else Toast.makeText(this, "Screen capture permission is required to monitor Market 346.", Toast.LENGTH_LONG).show();
        }
    }

    private void showMonitoring() {
        root = base("Market 346 Monitor");
        TextView pill = text("●  MONITORING", 14, green); pill.setPadding(14,12,14,12); root.addView(pill);
        TextView state = text("Status\nDETECTING...", 22, white); state.setPadding(0, 26, 0, 16); root.addView(state);
        TextView detail = text("Phone 1 is watching the selected Market 346 area. Detected state will be served to Phone 2.", 14, muted); root.addView(detail);
        Space s = new Space(this); root.addView(s, new LinearLayout.LayoutParams(1, 20));
        Button stop = button("STOP MONITORING", red); stop.setOnClickListener(v -> { stopService(new Intent(this, MonitorService.class)); showHome(); }); root.addView(stop);
        Button settings = button("SETTINGS / AREA", 0xFF2D3137); settings.setOnClickListener(v -> showMonitorSetup()); root.addView(settings);
        setContentView(root);
    }

    private LinearLayout base(String title) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(22, 18, 22, 22); l.setBackgroundColor(bg);
        TextView t = text(title, 24, white); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(0,0,0,24); l.addView(t); return l;
    }
    private TextView text(String s, int size, int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,8,0,8); return t; }
    private EditText field(String s) { EditText e=new EditText(this); e.setText(s); e.setTextColor(white); e.setHintTextColor(muted); e.setSingleLine(true); e.setPadding(16,10,16,10); e.setBackgroundColor(card); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,56); p.setMargins(0,0,0,14); e.setLayoutParams(p); return e; }
    private Button button(String s, int color) { Button b=new Button(this); b.setText(s); b.setTextColor(white); b.setTextSize(14); b.setAllCaps(false); b.setBackgroundColor(color); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,58); p.setMargins(0,8,0,8); b.setLayoutParams(p); return b; }
    private void startForegroundServiceCompat(Intent i) { if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }
    private void requestNotifications() { if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 900); }
}
