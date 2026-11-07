package com.apptesla.mirror;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Foreground service: screen + audio capture -> encoders -> local HTTP/WebSocket server. */
public class StreamService extends Service {
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data", ACTION_STOP = "stop";
    static final int PORT = 8080;
    private static final String TAG = "TeslaMirror";
    private static final String CHANNEL = "mirror";

    interface StatusListener { void onStatusChanged(); }
    static volatile boolean running;
    static volatile String lastError;
    static volatile StatusListener listener;

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile VideoEncoder video;
    private AudioEncoder audio;
    private StreamServer server;
    private DisplayManager.DisplayListener displayListener;
    private boolean landscape;
    private long t0Us;
    private boolean stopping;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || running) return START_NOT_STICKY;

        createChannel();
        // Android 14+: must be foreground with type mediaProjection BEFORE getMediaProjection().
        // Microphone type is added only when RECORD_AUDIO is granted (needed for audio capture in FGS).
        int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= 30
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            fgsType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        startForeground(1, buildNotification(), fgsType);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        else data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = data == null ? null : mpm.getMediaProjection(code, data);
        if (projection == null) {
            fail("Ekran yakalama izni alınamadı");
            return START_NOT_STICKY;
        }

        thread = new HandlerThread("mirror");
        thread.start();
        handler = new Handler(thread.getLooper());
        // Android 14+: callback must be registered before createVirtualDisplay().
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { handler.post(StreamService.this::stopSelf); }
        }, handler);

        running = true;
        lastError = null;
        notifyStatus();
        handler.post(this::startPipeline);
        return START_NOT_STICKY;
    }

    private void startPipeline() {
        try {
            t0Us = System.nanoTime() / 1000;
            server = new StreamServer(PORT, readAsset("index.html"), () -> {
                VideoEncoder v = video;
                if (v != null) v.requestKeyFrame();
            });
            server.start();

            startVideo();

            boolean audioOk = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    audio = new AudioEncoder(projection, t0Us, new AudioEncoder.Sink() {
                        @Override public void onAudioInit(byte[] init, String codec) { server.setAudioInit(init, codec); }
                        @Override public void onAudioFragment(byte[] f) { server.sendAudio(f); }
                    });
                    audio.start();
                    audioOk = true;
                } catch (Exception e) {
                    Log.w(TAG, "audio capture unavailable", e);
                    audio = null;
                }
            }
            if (!audioOk) server.setAudioExpected(false);

            DisplayManager dm = getSystemService(DisplayManager.class);
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) {}
                @Override public void onDisplayRemoved(int id) {}
                @Override public void onDisplayChanged(int id) {
                    if (id == Display.DEFAULT_DISPLAY) handler.post(StreamService.this::checkRotation);
                }
            };
            dm.registerDisplayListener(displayListener, handler);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            fail("Başlatılamadı: " + e.getMessage());
        }
    }

    private Point screenSize() {
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return new Point(m.widthPixels, m.heightPixels);
    }

    private int densityDpi() {
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return m.densityDpi;
    }

    private void startVideo() throws Exception {
        Point s = screenSize();
        landscape = s.x > s.y;
        VideoEncoder v = VideoEncoder.create(s.x, s.y, t0Us, new VideoEncoder.Sink() {
            @Override public void onVideoInit(byte[] init, String codec) { server.setVideoInit(init, codec); }
            @Override public void onVideoFragment(byte[] f, boolean key) { server.sendVideo(f, key); }
        });
        if (display == null) {
            display = projection.createVirtualDisplay("TeslaMirror", v.width, v.height, densityDpi(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, v.surface, null, handler);
        } else {
            display.resize(v.width, v.height, densityDpi());
            display.setSurface(v.surface);
        }
        video = v;
        v.start();
    }

    /** Phone rotated (e.g. full-screen video): rebuild encoder at the new orientation. */
    private void checkRotation() {
        if (stopping || video == null) return;
        Point s = screenSize();
        if ((s.x > s.y) == landscape) return;
        try {
            display.setSurface(null);
            VideoEncoder old = video;
            video = null;
            old.stop();
            startVideo();
        } catch (Exception e) {
            Log.e(TAG, "rotation restart failed", e);
            fail("Döndürme sonrası video yeniden başlatılamadı");
        }
    }

    private void fail(String msg) {
        lastError = msg;
        notifyStatus();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        if (handler != null) {
            // Tear down on the pipeline thread, then quit it.
            handler.post(this::teardown);
            thread.quitSafely();
        } else {
            teardown();
        }
        notifyStatus();
        super.onDestroy();
    }

    private void teardown() {
        try {
            if (displayListener != null) getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {}
        if (audio != null) { audio.stop(); audio = null; }
        VideoEncoder v = video;
        video = null;
        if (display != null) { display.setSurface(null); display.release(); display = null; }
        if (v != null) v.stop();
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
        if (server != null) { server.stop(); server = null; }
    }

    private void notifyStatus() {
        StatusListener l = listener;
        if (l != null) l.onStatusChanged();
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, StreamService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("TeslaMirror yayında")
                .setContentText("Tesla tarayıcısında uygulamadaki adresi açın")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Durdur", stop).build())
                .setOngoing(true)
                .build();
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
