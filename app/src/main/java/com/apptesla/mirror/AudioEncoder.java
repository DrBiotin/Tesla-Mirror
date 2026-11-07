package com.apptesla.mirror;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Internal audio (AudioPlaybackCapture, Android 10+) -> AAC-LC -> fMP4 fragments.
 * AudioPacer turns irregular delivery into gap-free, capture-timestamped chunks.
 */
final class AudioEncoder {
    interface Sink {
        void onAudioInit(byte[] init, String codec);
        void onAudioFragment(byte[] fragment);
    }

    private static final String TAG = "TeslaMirror";
    static final int RATE = 48000, CHANNELS = 2;
    private static final int FRAME_BYTES = CHANNELS * 2;          // 16-bit stereo
    private static final int CHUNK = AudioPacer.CHUNK;            // 1024 samples ≈ 21.3 ms

    private final AudioRecord rec;
    private final MediaCodec codec;
    private final long t0Us;
    private final Sink sink;
    private volatile boolean stop;
    private Thread inThread, outThread;

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked by caller
    AudioEncoder(MediaProjection projection, long t0Us, Sink sink) throws Exception {
        this.t0Us = t0Us;
        this.sink = sink;
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min * 2, CHUNK * 8))
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("AudioRecord başlatılamadı");
        }
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2);
        MediaCodec c;
        try {
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        } catch (Exception e) {
            rec.release();
            throw e;
        }
        codec = c;
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            codec.release();
            rec.release();
            throw e;
        }
    }

    void start() {
        rec.startRecording();
        codec.start();
        inThread = new Thread(this::feed, "audio-in");
        outThread = new Thread(this::drain, "audio-out");
        inThread.start();
        outThread.start();
    }

    void stop() {
        stop = true;
        try { if (inThread != null) inThread.join(1000); } catch (InterruptedException ignored) {}
        try { if (outThread != null) outThread.join(1000); } catch (InterruptedException ignored) {}
        try { rec.stop(); } catch (Exception ignored) {}
        rec.release();
        try { codec.stop(); } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }

    private void feed() {
        AudioTimestamp ts = new AudioTimestamp();
        final AudioPacer[] self = new AudioPacer[1];
        // Capture time of a frame from AudioRecord's own timestamp (fallback: estimate from backlog).
        AudioPacer.Clock clock = (frame, nowNs) -> {
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                long t = ts.nanoTime + (frame - ts.framePosition) * 1_000_000_000L / RATE;
                if (Math.abs(t - nowNs) < 1_000_000_000L) return t;
            }
            return nowNs - (self[0].readFrames() - frame) * 1_000_000_000L / RATE;
        };
        AudioPacer pacer = new AudioPacer(clock, (pcm, off, ptsNs, silent) -> queue(pcm, off, ptsNs / 1000));
        self[0] = pacer;
        byte[] buf = new byte[CHUNK * 4];
        try {
            while (!stop) {
                int n = rec.read(buf, 0, Math.min(buf.length, pacer.free()), AudioRecord.READ_NON_BLOCKING);
                if (n > 0) pacer.append(buf, 0, n);
                pacer.process(System.nanoTime());
                if (n <= 0) Thread.sleep(5);
            }
        } catch (Exception e) {
            if (!stop) Log.e(TAG, "audio feed error", e);
        }
    }

    private void queue(byte[] pcm, int off, long ptsUs) {
        int idx = -1;
        while (!stop && idx < 0) idx = codec.dequeueInputBuffer(20_000);
        if (idx < 0) return;
        ByteBuffer ib = codec.getInputBuffer(idx);
        if (ib == null) return;
        ib.clear();
        ib.put(pcm, off, CHUNK);
        codec.queueInputBuffer(idx, 0, CHUNK, ptsUs, 0);
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inited = false;
        long next = -1; // in 48 kHz units
        int seq = 1;
        try {
            while (!stop) {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx < 0) continue;
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
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    sink.onAudioInit(Fmp4.audioInit(data, RATE, CHANNELS), "mp4a.40.2");
                    inited = true;
                    continue;
                }
                if (!inited) continue;
                long t = Math.max(0, (pts - t0Us) * RATE / 1_000_000);
                if (next < 0 || Math.abs(t - next) > RATE / 10) next = t; // resync if >100 ms off
                sink.onAudioFragment(Fmp4.fragment(seq++, next, 1024, data, Fmp4.FLAGS_SYNC));
                next += 1024;
            }
        } catch (Exception e) {
            if (!stop) Log.e(TAG, "audio drain error", e);
        }
    }
}
