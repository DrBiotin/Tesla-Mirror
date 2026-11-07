package com.apptesla.mirror;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** H.264 Annex-B (start-code) helpers. */
final class AnnexB {
    private AnnexB() {}

    static int type(byte[] nal) { return nal.length > 0 ? nal[0] & 0x1F : -1; }

    /** Splits an Annex-B buffer into NAL units without start codes. */
    static List<byte[]> split(byte[] b, int off, int len) {
        List<byte[]> out = new ArrayList<>();
        int end = off + len;
        int nalStart = -1;
        int i = off;
        while (i + 2 < end) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                if (nalStart >= 0) add(out, b, nalStart, i);
                i += 3;
                nalStart = i;
            } else {
                i++;
            }
        }
        if (nalStart >= 0) add(out, b, nalStart, end);
        else if (len > 0) add(out, b, off, end); // no start code: treat as one NAL
        return out;
    }

    private static void add(List<byte[]> out, byte[] b, int s, int e) {
        while (e > s && b[e - 1] == 0) e--; // trailing zeros / leading zero of 4-byte start code
        if (e > s) {
            byte[] n = new byte[e - s];
            System.arraycopy(b, s, n, 0, n.length);
            out.add(n);
        }
    }

    /** 4-byte length-prefixed sample; drops SPS(7), PPS(8), AUD(9). */
    static byte[] toAvcc(List<byte[]> nals) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] n : nals) {
            int t = type(n);
            if (t == 7 || t == 8 || t == 9) continue;
            int l = n.length;
            o.write(l >>> 24); o.write(l >>> 16); o.write(l >>> 8); o.write(l);
            o.write(n, 0, l);
        }
        return o.toByteArray();
    }
}
