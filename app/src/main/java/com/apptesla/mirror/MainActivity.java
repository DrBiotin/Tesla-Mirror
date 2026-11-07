package com.apptesla.mirror;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1, REQ_CAPTURE = 2;
    private static final int LINK_BLUE = Color.rgb(77, 163, 255);

    private LinearLayout root;
    private TextView info, notes, langButton;
    private LinearLayout links;
    private Button button;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("TeslaMirror");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        info = text(17, Color.rgb(220, 220, 220));
        root.addView(info, block(24));

        links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        root.addView(links, block(8));

        notes = text(15, Color.rgb(180, 180, 180));
        root.addView(notes, block(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);

        // Round language badge, always at the top-right corner as the user sees it.
        langButton = new TextView(this);
        langButton.setGravity(Gravity.CENTER);
        langButton.setTextColor(Color.WHITE);
        langButton.setTextSize(14);
        langButton.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(45, 45, 45));
        circle.setStroke(dp(2), Color.rgb(200, 200, 200));
        langButton.setBackground(circle);
        langButton.setOnClickListener(v -> showLanguageList());

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(18, 18, 18));
        frame.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.RIGHT);
        bp.topMargin = dp(12);
        bp.rightMargin = dp(12);
        frame.addView(langButton, bp);
        frame.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // keep the badge on the right even in Arabic

        // targetSdk 35+ is edge-to-edge: pad by system bars/cutout.
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(i.left, i.top, i.right, i.bottom);
            }
            return insets;
        });
        setContentView(frame);
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private String t(String key) { return I18n.t(this, key); }

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

    private void showLanguageList() {
        String[] items = new String[I18n.CODES.length];
        for (int i = 0; i < items.length; i++)
            items[i] = I18n.NAMES[i] + "  (" + I18n.CODES[i].toUpperCase(Locale.ROOT) + ")";
        int cur = I18n.index(I18n.current(this));
        new AlertDialog.Builder(this)
                .setTitle(t("lang_title"))
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    I18n.set(this, I18n.CODES[which]);
                    d.dismiss();
                    StreamService.onLanguageChanged();
                    refresh();
                })
                .show();
    }

    private void refresh() {
        boolean rtl = I18n.rtl(this);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        int start = rtl ? Gravity.RIGHT : Gravity.LEFT;
        info.setGravity(start);
        notes.setGravity(start);
        langButton.setText(I18n.current(this).toUpperCase(Locale.ROOT));

        boolean on = StreamService.running;
        button.setText(t(on ? "stop" : "start"));

        StringBuilder s = new StringBuilder();
        if (StreamService.lastError != null) {
            s.append("⚠ ").append(t(StreamService.lastError));
            if (StreamService.lastErrorDetail != null) s.append(StreamService.lastErrorDetail);
            s.append("\n\n");
        }
        links.removeAllViews();
        if (on) {
            s.append(t("open_in_tesla"));
            List<String[]> ips = ipv4();
            if (ips.isEmpty()) {
                s.append("\n\n").append(t("no_address"));
            }
            for (String[] ip : ips) links.addView(linkView("http://" + ip[0] + ":" + StreamService.PORT, ip[1]));
            notes.setText(ips.isEmpty() ? t("hotspot_hint") : t("copy_hint") + "\n" + t("hotspot_hint"));
        } else {
            s.append(t("step1")).append('\n').append(t("step2")).append('\n')
             .append(t("step3")).append('\n').append(t("step4"));
            notes.setText(t("notes_title") + "\n" + t("note_lock") + "\n" + t("note_drm") + "\n" + t("note_drive"));
        }
        info.setText(s.toString());
    }

    /** Blue, underlined address; long-press shows a "Copy" menu next to it. */
    private View linkView(String url, String iface) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView link = new TextView(this);
        link.setText(url);
        link.setTextSize(20);
        link.setTextColor(LINK_BLUE);
        link.setPaintFlags(link.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        link.setTextDirection(View.TEXT_DIRECTION_LTR);
        link.setPadding(0, dp(4), 0, dp(4));
        link.setOnLongClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v, Gravity.END);
            m.getMenu().add(t("copy"));
            m.setOnMenuItemClickListener(item -> {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("TeslaMirror", url));
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, t("copied"), Toast.LENGTH_SHORT).show();
                return true;
            });
            m.show();
            return true;
        });
        row.addView(link);

        TextView label = new TextView(this);
        label.setText(iface);
        label.setTextSize(13);
        label.setTextColor(Color.rgb(150, 150, 150));
        row.addView(label);
        return row;
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
            Toast.makeText(this, t("toast_no_audio"), Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, t("toast_no_notif"), Toast.LENGTH_LONG).show();
        launchCapture();
    }

    private void launchCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        // Entire screen only: the system dialog's "single app" choice is disabled by this config.
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
            Toast.makeText(this, t("toast_capture_denied"), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(this, StreamService.class)
                .putExtra(StreamService.EXTRA_CODE, result)
                .putExtra(StreamService.EXTRA_DATA, data);
        startForegroundService(s);
    }

    /** {ip, interface label}; hotspot interfaces first. */
    private static List<String[]> ipv4() {
        List<String[]> r = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String n = ni.getName();
                        boolean hotspot = n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.equals("wlan1");
                        String[] entry = {a.getHostAddress(), n + (hotspot ? " · hotspot" : "")};
                        if (hotspot) r.add(0, entry); else r.add(entry);
                    }
                }
            }
        } catch (Exception ignored) {}
        return r;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
