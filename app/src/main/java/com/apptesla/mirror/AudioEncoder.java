package com.apptesla.mirror;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Internal audio (AudioPlaybackCapture, Android 10+) -> AAC-LC -> fMP4 fragments.
 * Audio is paced by the wall clock: silence is inserted when nothing plays and the
 * backlog is capped, so the player's audio track never stalls or drifts.
 */
final class AudioEncoder {
    interface Sink {
        void onAudioInit(byte[] init, String codec);
        void onAudioFragment(byte[] fragment);
    }

    private static final String TAG = "TeslaMirror";
    static final int RATE = 48000, CHANNELS = 2;
    private static final int FRAME_BYTES = CHANNELS * 2;          // 16-bit stereo
    private static final int CHUNK = 1024 * FRAME_BYTES;          // 1024 samples ≈ 21.3 ms
    private static final long CHUNK_NS = 1024L * 1_000_000_000L / RATE;

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
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
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
        byte[] acc = new byte[CHUNK * 6];
        int accLen = 0;
        byte[] out = new byte[CHUNK];
        long startNs = System.nanoTime();
        long k = 0;
        try {
            while (!stop) {
                long due = startNs + (k + 1) * CHUNK_NS;   // chunk k ends at 'due'
                long wait = due - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                else if (-wait > 200_000_000L) {           // fell far behind: resync clock
                    startNs = System.nanoTime() - CHUNK_NS;
                    k = 0;
                }

                // Pull everything currently available (non-blocking).
                while (true) {
                    if (accLen == acc.length) {            // cap latency: drop oldest chunk
                        System.arraycopy(acc, CHUNK, acc, 0, accLen - CHUNK);
                        accLen -= CHUNK;
                    }
                    int n = rec.read(acc, accLen, acc.length - accLen, AudioRecord.READ_NON_BLOCKING);
                    if (n <= 0) break;
                    accLen += n;
                }
                // Keep backlog small (<= ~85 ms).
                while (accLen > CHUNK * 4) {
                    System.arraycopy(acc, CHUNK, acc, 0, accLen - CHUNK);
                    accLen -= CHUNK;
                }
                int take = Math.min(accLen, CHUNK) & ~(FRAME_BYTES - 1);
                System.arraycopy(acc, 0, out, 0, take);
                java.util.Arrays.fill(out, take, CHUNK, (byte) 0); // silence padding
                System.arraycopy(acc, take, acc, 0, accLen - take);
                accLen -= take;

                long ptsUs = (startNs + k * CHUNK_NS) / 1000;
                k++;
                int idx = -1;
                while (!stop && idx < 0) idx = codec.dequeueInputBuffer(20_000);
                if (idx < 0) break;
                ByteBuffer ib = codec.getInputBuffer(idx);
                if (ib == null) continue;
                ib.clear();
                ib.put(out, 0, CHUNK);
                codec.queueInputBuffer(idx, 0, CHUNK, ptsUs, 0);
            }
        } catch (Exception e) {
            if (!stop) Log.e(TAG, "audio feed error", e);
        }
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
