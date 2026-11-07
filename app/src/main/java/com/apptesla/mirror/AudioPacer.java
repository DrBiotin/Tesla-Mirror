package com.apptesla.mirror;

/**
 * Turns irregular PCM delivery into a gap-free sequence of 1024-sample chunks with
 * capture-time timestamps (System.nanoTime base).
 *  - Normal jitter: chunks are emitted only when full, so no silence is ever inserted
 *    into continuous audio.
 *  - Real silence (no data arriving): silent chunks keep the timeline near real time,
 *    so the browser's audio track never stalls.
 *  - Data older than the timeline (late burst after silence fill) is dropped; a timeline
 *    behind the capture clock is filled with silence. This bounds latency and drift.
 * Pure Java (no Android APIs) so it can be unit-tested off-device.
 */
final class AudioPacer {
    interface Clock { long captureNs(long frameIndex, long nowNs); }
    interface Out { void emit(byte[] pcm, int off, long ptsNs, boolean silence); }

    static final int RATE = 48000, FRAME_BYTES = 4;              // 16-bit stereo
    static final int CHUNK = 1024 * FRAME_BYTES;
    static final long CHUNK_NS = 1024L * 1_000_000_000L / RATE;  // 21.333 ms
    static final long RESYNC_NS = 60_000_000L;
    static final long SILENCE_AFTER_NS = 150_000_000L;
    static final long MAX_FILL_NS = 1_000_000_000L;

    private final byte[] acc = new byte[CHUNK * 16];
    private final byte[] silence = new byte[CHUNK];
    private int accLen;
    private long readFrames;
    private long tNs = Long.MIN_VALUE;
    private final Clock clock;
    private final Out out;

    AudioPacer(Clock clock, Out out) { this.clock = clock; this.out = out; }

    long readFrames() { return readFrames; }
    int backlogFrames() { return accLen / FRAME_BYTES; }
    int free() { return acc.length - accLen; }

    void append(byte[] b, int off, int n) {
        n -= n % FRAME_BYTES;
        if (n <= 0) return;
        if (n > acc.length) { off += n - acc.length; readFrames += (n - acc.length) / FRAME_BYTES; n = acc.length; }
        if (accLen + n > acc.length) drop(accLen + n - acc.length);
        System.arraycopy(b, off, acc, accLen, n);
        accLen += n;
        readFrames += n / FRAME_BYTES;
    }

    void process(long nowNs) {
        while (true) {
            if (accLen >= CHUNK) {
                long head = readFrames - accLen / FRAME_BYTES;
                long cap = clock.captureNs(head, nowNs);
                if (tNs == Long.MIN_VALUE) tNs = cap;
                long d = cap - tNs;
                if (d > RESYNC_NS) {
                    if (d > MAX_FILL_NS) tNs = cap;
                    else { emit(silence, 0, true); continue; }
                } else if (d < -RESYNC_NS) {
                    drop(CHUNK);
                    continue;
                }
                emit(acc, 0, false);
                drop(CHUNK);
            } else {
                if (tNs != Long.MIN_VALUE && nowNs - tNs > SILENCE_AFTER_NS) { emit(silence, 0, true); continue; }
                return;
            }
        }
    }

    private void emit(byte[] b, int off, boolean isSilence) {
        out.emit(b, off, tNs, isSilence);
        tNs += CHUNK_NS;
    }

    private void drop(int bytes) {
        bytes = Math.min(bytes, accLen);
        System.arraycopy(acc, bytes, acc, 0, accLen - bytes);
        accLen -= bytes;
    }
}
