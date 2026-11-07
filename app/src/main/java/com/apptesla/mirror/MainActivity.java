package com.apptesla.mirror;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1, REQ_CAPTURE = 2;
    private TextView info;
    private Button button;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TeslaMirror");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        button = new Button(this);
        button.setTextSize(20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(28);
        root.addView(button, lp);
        button.setOnClickListener(v -> {
            if (StreamService.running) {
                startService(new Intent(this, StreamService.class).setAction(StreamService.ACTION_STOP));
            } else {
                requestPermsThenCapture();
            }
        });

        info = new TextView(this);
        info.setTextSize(17);
        info.setTextColor(Color.rgb(220, 220, 220));
        info.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(24);
        root.addView(info, ip);

        // targetSdk 35+ is edge-to-edge: pad by system bars/cutout and allow scrolling.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 18));
        scroll.setFillViewport(true);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(i.left, i.top, i.right, i.bottom);
            }
            return insets;
        });
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StreamService.listener = () -> runOnUiThread(this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        StreamService.listener = null;
        super.onPause();
    }

    private void refresh() {
        boolean on = StreamService.running;
        button.setText(on ? "■  Yayını durdur" : "▶  Yayını başlat");
        StringBuilder s = new StringBuilder();
        if (StreamService.lastError != null) s.append("⚠ ").append(StreamService.lastError).append("\n\n");
        if (on) {
            s.append("Tesla tarayıcısında şu adreslerden birini açın:\n\n");
            List<String> ips = ipv4();
            if (ips.isEmpty()) s.append("(Ağ adresi bulunamadı — hotspot açık mı?)\n");
            for (String ip : ips) {
                String[] p = ip.split(" ");
                s.append("http://").append(p[0]).append(':').append(StreamService.PORT)
                 .append("   (").append(p[1]).append(")\n");
            }
            s.append("\nGenelde hotspot adresi 192.168.x.x veya 10.x.x.x ile başlar.");
        } else {
            s.append("1. Telefonda Wi-Fi hotspot'u açın.\n")
             .append("2. Tesla'yı bu hotspot'a bağlayın (Kontroller > Wi-Fi).\n")
             .append("3. \"Yayını başlat\"a basın, \"Tüm ekran\"ı seçin.\n")
             .append("4. Görünen adresi Tesla tarayıcısında açıp ▶ Başlat'a dokunun.\n\n")
             .append("Notlar:\n• Yayın sırasında telefon ekranı açık tutulur; telefonu kilitlerseniz yayın durur.\n")
             .append("• Netflix gibi korumalı (DRM) uygulamalar siyah ekran/sessiz görünür.\n")
             .append("• Tesla sürüşte tarayıcı videosunu gizleyebilir; ses devam eder.");
        }
        info.setText(s.toString());
    }

    private void requestPermsThenCapture() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) launchCapture();
        else requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Ses izni verilmedi: yalnızca görüntü aktarılacak", Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Bildirim izni yok: yayını durdurmak için uygulamaya dönün", Toast.LENGTH_LONG).show();
        launchCapture();
    }

    private void launchCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Ekran yakalama izni verilmedi", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(this, StreamService.class)
                .putExtra(StreamService.EXTRA_CODE, result)
                .putExtra(StreamService.EXTRA_DATA, data);
        startForegroundService(s);
    }

    private static List<String> ipv4() {
        List<String> r = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String n = ni.getName();
                        boolean hotspot = n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.equals("wlan1");
                        String entry = a.getHostAddress() + " " + n + (hotspot ? "·hotspot" : "");
                        if (hotspot) r.add(0, entry); else r.add(entry);
                    }
                }
            }
        } catch (Exception ignored) {}
        return r;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
