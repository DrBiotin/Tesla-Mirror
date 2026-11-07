package com.apptesla.mirror;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal fragmented-MP4 (ISO BMFF) writer for Media Source Extensions.
 * One track per init segment, one sample per fragment (lowest latency).
 */
final class Fmp4 {
    static final int TRACK_ID = 1;
    static final int VIDEO_TIMESCALE = 90000;
    static final int FLAGS_SYNC = 0x02000000;      // sample_depends_on = 2 (I-frame)
    static final int FLAGS_NON_SYNC = 0x01010000;  // depends_on = 1, is_non_sync_sample = 1

    private Fmp4() {}

    // ---------- byte helpers ----------
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        W u8(int v) { o.write(v & 0xFF); return this; }
        W u16(int v) { u8(v >>> 8); u8(v); return this; }
        W u24(int v) { u8(v >>> 16); u8(v >>> 8); u8(v); return this; }
        W u32(long v) { u8((int) (v >>> 24)); u8((int) (v >>> 16)); u8((int) (v >>> 8)); u8((int) v); return this; }
        W u64(long v) { u32(v >>> 32); u32(v & 0xFFFFFFFFL); return this; }
        W zeros(int n) { for (int i = 0; i < n; i++) o.write(0); return this; }
        W str(String s) { byte[] b = s.getBytes(StandardCharsets.US_ASCII); o.write(b, 0, b.length); return this; }
        W bytes(byte[] b) { o.write(b, 0, b.length); return this; }
        W bytes(byte[] b, int off, int len) { o.write(b, off, len); return this; }
        byte[] get() { return o.toByteArray(); }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, off, p.length); off += p.length; }
        return r;
    }

    static byte[] box(String type, byte[]... payload) {
        byte[] body = concat(payload);
        return new W().u32(8 + body.length).str(type).bytes(body).get();
    }

    static byte[] fullBox(String type, int version, int flags, byte[]... payload) {
        byte[] head = new W().u8(version).u24(flags).get();
        byte[][] all = new byte[payload.length + 1][];
        all[0] = head;
        System.arraycopy(payload, 0, all, 1, payload.length);
        return box(type, all);
    }

    private static W matrix(W w) {
        return w.u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000);
    }

    private static byte[] ftyp() {
        return box("ftyp", new W().str("iso5").u32(512).str("iso5").str("iso6").str("mp41").get());
    }

    private static byte[] moov(int timescale, boolean video, int width, int height, byte[] sampleEntry) {
        byte[] mvhd = fullBox("mvhd", 0, 0, matrix(new W().u32(0).u32(0).u32(1000).u32(0)
                .u32(0x00010000).u16(0x0100).zeros(10)).zeros(24).u32(TRACK_ID + 1).get());
        byte[] tkhd = fullBox("tkhd", 0, 3, matrix(new W().u32(0).u32(0).u32(TRACK_ID).u32(0).u32(0)
                .zeros(8).u16(0).u16(0).u16(video ? 0 : 0x0100).u16(0))
                .u32((long) width << 16).u32((long) height << 16).get());
        byte[] mdhd = fullBox("mdhd", 0, 0, new W().u32(0).u32(0).u32(timescale).u32(0).u16(0x55C4).u16(0).get());
        byte[] hdlr = fullBox("hdlr", 0, 0, new W().u32(0).str(video ? "vide" : "soun").zeros(12)
                .str(video ? "VideoHandler" : "SoundHandler").u8(0).get());
        byte[] xmhd = video ? fullBox("vmhd", 0, 1, new W().zeros(8).get())
                            : fullBox("smhd", 0, 0, new W().zeros(4).get());
        byte[] dinf = box("dinf", fullBox("dref", 0, 0, new W().u32(1).get(), fullBox("url ", 0, 1)));
        byte[] stbl = box("stbl",
                fullBox("stsd", 0, 0, new W().u32(1).get(), sampleEntry),
                fullBox("stts", 0, 0, new W().u32(0).get()),
                fullBox("stsc", 0, 0, new W().u32(0).get()),
                fullBox("stsz", 0, 0, new W().u32(0).u32(0).get()),
                fullBox("stco", 0, 0, new W().u32(0).get()));
        byte[] minf = box("minf", xmhd, dinf, stbl);
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        byte[] trak = box("trak", tkhd, mdia);
        byte[] mvex = box("mvex", fullBox("trex", 0, 0, new W().u32(TRACK_ID).u32(1).u32(0).u32(0).u32(0).get()));
        return box("moov", mvhd, trak, mvex);
    }

    /** sps/pps: raw NAL units (no start code, NAL header byte included). */
    static byte[] videoInit(byte[] sps, byte[] pps, int width, int height) {
        int profile = sps[1] & 0xFF;
        W c = new W().u8(1).u8(profile).u8(sps[2] & 0xFF).u8(sps[3] & 0xFF)
                .u8(0xFF)            // lengthSizeMinusOne = 3
                .u8(0xE1).u16(sps.length).bytes(sps)
                .u8(1).u16(pps.length).bytes(pps);
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            // High profiles: 4:2:0, 8-bit (Android hardware AVC encoders), no SPS-ext.
            c.u8(0xFC | 1).u8(0xF8).u8(0xF8).u8(0);
        }
        byte[] avcC = box("avcC", c.get());
        byte[] avc1 = box("avc1", new W().zeros(6).u16(1).u16(0).u16(0).zeros(12)
                .u16(width).u16(height).u32(0x00480000).u32(0x00480000).u32(0).u16(1)
                .zeros(32).u16(0x18).u16(0xFFFF).get(), avcC);
        return concat(ftyp(), moov(VIDEO_TIMESCALE, true, width, height, avc1));
    }

    static String videoCodec(byte[] sps) {
        return String.format("avc1.%02x%02x%02x", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
    }

    private static byte[] desc(int tag, byte[] payload) {
        W w = new W().u8(tag);
        int n = payload.length;
        // 4-byte expandable length (always valid)
        w.u8(0x80 | ((n >>> 21) & 0x7F)).u8(0x80 | ((n >>> 14) & 0x7F)).u8(0x80 | ((n >>> 7) & 0x7F)).u8(n & 0x7F);
        return w.bytes(payload).get();
    }

    /** asc: AudioSpecificConfig (MediaCodec CODEC_CONFIG output / csd-0). */
    static byte[] audioInit(byte[] asc, int sampleRate, int channels) {
        byte[] dsi = desc(0x05, asc);
        byte[] dcd = desc(0x04, concat(new W().u8(0x40).u8(0x15).u24(0).u32(0).u32(0).get(), dsi));
        byte[] sl = desc(0x06, new byte[]{0x02});
        byte[] es = desc(0x03, concat(new W().u16(TRACK_ID).u8(0).get(), dcd, sl));
        byte[] esds = fullBox("esds", 0, 0, es);
        byte[] mp4a = box("mp4a", new W().zeros(6).u16(1).zeros(8).u16(channels).u16(16).u16(0).u16(0)
                .u32((long) sampleRate << 16).get(), esds);
        return concat(ftyp(), moov(sampleRate, false, 0, 0, mp4a));
    }

    /** One moof+mdat holding a single sample. */
    static byte[] fragment(int seq, long baseDecodeTime, int duration, byte[] sample, int sampleFlags) {
        byte[] mfhd = fullBox("mfhd", 0, 0, new W().u32(seq).get());
        byte[] tfhd = fullBox("tfhd", 0, 0x020000, new W().u32(TRACK_ID).get()); // default-base-is-moof
        byte[] tfdt = fullBox("tfdt", 1, 0, new W().u64(baseDecodeTime).get());
        byte[] trun = fullBox("trun", 0, 0x000701, new W().u32(1).u32(0)
                .u32(duration).u32(sample.length).u32(sampleFlags).get());
        byte[] moof = box("moof", mfhd, box("traf", tfhd, tfdt, trun));
        // trun is the last box: [... count(4) data_offset(4) dur(4) size(4) flags(4)]
        ByteBuffer.wrap(moof).putInt(moof.length - 16, moof.length + 8);
        byte[] mdatHead = new W().u32(8 + sample.length).str("mdat").get();
        return concat(moof, mdatHead, sample);
    }
}
