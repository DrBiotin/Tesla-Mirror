package com.apptesla.mirror;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** Screen frames (input Surface) -> H.264 -> fMP4 fragments. */
final class VideoEncoder {
    interface Sink {
        void onVideoInit(byte[] init, String codec);
        void onVideoFragment(byte[] fragment, boolean key);
    }

    private static final String TAG = "TeslaMirror";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int[] MAX_SIDES = {1920, 1600, 1280, 960};

    final int width, height;
    final Surface surface;
    private final MediaCodec codec;
    private final long t0Us;
    private final Sink sink;
    private volatile boolean stop;
    private Thread thread;

    private VideoEncoder(MediaCodec codec, int w, int h, long t0Us, Sink sink) {
        this.codec = codec; this.width = w; this.height = h; this.t0Us = t0Us; this.sink = sink;
        this.surface = codec.createInputSurface();
    }

    /** Picks the largest size (long side <= 1920) the hardware encoder accepts. */
    static VideoEncoder create(int srcW, int srcH, long t0Us, Sink sink) throws IOException {
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaCodecInfo.VideoCapabilities caps =
                codec.getCodecInfo().getCapabilitiesForType(MIME).getVideoCapabilities();
        for (int max : MAX_SIDES) {
            double scale = Math.min(1.0, (double) max / Math.max(srcW, srcH));
            int w = ((int) (srcW * scale)) & ~15;
            int h = ((int) (srcH * scale)) & ~15;
            if (w < 160 || h < 160) continue;
            if (caps != null && !caps.areSizeAndRateSupported(w, h, 30)) continue;
            boolean ok = false;
            for (boolean repeat : new boolean[]{true, false}) { // some encoders reject REPEAT_PREVIOUS_FRAME
                try {
                    codec.configure(format(w, h, repeat), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    ok = true;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "configure failed for " + w + "x" + h + " repeat=" + repeat, e);
                    codec.reset();
                }
            }
            if (!ok) continue;
            try {
                Log.i(TAG, "video encoder " + codec.getName() + " " + w + "x" + h);
                return new VideoEncoder(codec, w, h, t0Us, sink);
            } catch (Exception e) {          // createInputSurface failed
                codec.release();
                throw new IOException("Video kodlayıcı yüzeyi oluşturulamadı", e);
            }
        }
        codec.release();
        throw new IOException("Uygun video kodlayıcı boyutu bulunamadı");
    }

    private static MediaFormat format(int w, int h, boolean repeat) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        int bitrate = (int) Math.max(2_000_000, Math.min(8_000_000, (long) w * h * 30 / 6));
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // Static screen still produces frames, so the player never starves.
        if (repeat) f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        return f;
    }

    void start() {
        codec.start();
        thread = new Thread(this::drain, "video-enc");
        thread.start();
    }

    void requestKeyFrame() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {}
    }

    void stop() {
        stop = true;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) {}
        }
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
        surface.release();
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] sps = null, pps = null;
        boolean inited = false;
        long offsetUs = Long.MIN_VALUE;
        int seq = 1;
        long pendT = -1, lastEnd = 0, pendArrivalNs = 0; byte[] pendSample = null; boolean pendKey = false;
        try {
            while (!stop) {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Some encoders deliver SPS/PPS only as csd-0/csd-1 in the output format.
                    MediaFormat of = codec.getOutputFormat();
                    for (String k : new String[]{"csd-0", "csd-1"}) {
                        ByteBuffer c = of.containsKey(k) ? of.getByteBuffer(k) : null;
                        if (c == null) continue;
                        byte[] b = new byte[c.remaining()];
                        c.duplicate().get(b);
                        for (byte[] n : AnnexB.split(b, 0, b.length)) {
                            if (AnnexB.type(n) == 7 && sps == null) sps = n;
                            else if (AnnexB.type(n) == 8 && pps == null) pps = n;
                        }
                    }
                    if (!inited && sps != null && pps != null) {
                        sink.onVideoInit(Fmp4.videoInit(sps, pps, width, height), Fmp4.videoCodec(sps));
                        inited = true;
                    }
                    continue;
                }
                if (idx < 0) {
                    // Screen went still: don't hold the last frame back, publish it now.
                    if (!stop && pendSample != null && System.nanoTime() - pendArrivalNs > 50_000_000L) {
                        long nowT = (System.nanoTime() / 1000 - t0Us) * 9 / 100;
                        int dur = (int) Math.max(3000, Math.min(nowT - pendT, 90_000));
                        sink.onVideoFragment(Fmp4.fragment(seq++, pendT, dur, pendSample,
                                pendKey ? Fmp4.FLAGS_SYNC : Fmp4.FLAGS_NON_SYNC), pendKey);
                        lastEnd = pendT + dur;
                        pendSample = null;
                    }
                    continue;
                }
                ByteBuffer bb = codec.getOutputBuffer(idx);
                byte[] data = new byte[info.size];
                if (bb != null && info.size > 0) {
                    bb.position(info.offset);
                    bb.limit(info.offset + info.size);
                    bb.get(data);
                }
                int flags = info.flags;
                long pts = info.presentationTimeUs;
                codec.releaseOutputBuffer(idx, false);
                if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                if (data.length == 0) continue;

                List<byte[]> nals = AnnexB.split(data, 0, data.length);
                boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                for (byte[] n : nals) {
                    int t = AnnexB.type(n);
                    if (t == 7) sps = n;
                    else if (t == 8) pps = n;
                    else if (t == 5) key = true;
                }
                if (!inited && sps != null && pps != null) {
                    sink.onVideoInit(Fmp4.videoInit(sps, pps, width, height), Fmp4.videoCodec(sps));
                    inited = true;
                }
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || !inited) continue;

                byte[] sample = AnnexB.toAvcc(nals);
                if (sample.length == 0) continue;

                if (offsetUs == Long.MIN_VALUE) {
                    long now = System.nanoTime() / 1000;
                    offsetUs = Math.abs(now - pts) > 1_000_000 ? now - pts : 0;
                    if (offsetUs != 0) Log.w(TAG, "video pts not on monotonic clock, offset " + offsetUs);
                }
                long t = Math.max(0, (pts + offsetUs - t0Us) * 9 / 100); // us -> 90 kHz
                if (pendSample == null && t <= lastEnd - 3000) t = lastEnd; // never go backwards after an idle flush

                if (stop) break;                 // stopped encoder must not emit anymore
                if (pendSample != null) {
                    if (t <= pendT) t = pendT + 1;
                    int dur = (int) Math.min(t - pendT, 90_000);
                    sink.onVideoFragment(Fmp4.fragment(seq++, pendT, dur, pendSample,
                            pendKey ? Fmp4.FLAGS_SYNC : Fmp4.FLAGS_NON_SYNC), pendKey);
                }
                pendT = t; pendSample = sample; pendKey = key; pendArrivalNs = System.nanoTime();
            }
        } catch (Exception e) {
            if (!stop) Log.e(TAG, "video drain error", e);
        }
    }
}
