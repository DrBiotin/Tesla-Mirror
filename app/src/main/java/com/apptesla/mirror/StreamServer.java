package com.apptesla.mirror;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Tiny dependency-free HTTP + WebSocket server.
 * GET /    -> player page
 * GET /ws  -> WebSocket; text = JSON config, binary = [type byte][payload]
 *            type 1 video init, 2 video fragment, 3 audio init, 4 audio fragment
 */
final class StreamServer {
    interface KeyFrameRequester { void requestKeyFrame(); }

    static final byte T_VIDEO_INIT = 1, T_VIDEO = 2, T_AUDIO_INIT = 3, T_AUDIO = 4;
    private static final int QUEUE_LIMIT = 240;
    private static final byte[] PING = frame(0x9, new byte[0]);

    private final int port;
    private final byte[] page;
    private final KeyFrameRequester keyReq;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile boolean running;

    // Current stream state (guarded by this)
    private String videoCodec, audioCodec;
    private byte[] videoInitFrame, audioInitFrame;
    private boolean audioExpected = true;

    StreamServer(int port, byte[] page, KeyFrameRequester keyReq) {
        this.port = port;
        this.page = page;
        this.keyReq = keyReq;
    }

    void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        IOException last = null;
        for (int i = 0; i < 10; i++) {          // previous session may still be releasing the port
            try { ss.bind(new InetSocketAddress(port)); last = null; break; }
            catch (IOException e) { last = e; try { Thread.sleep(300); } catch (InterruptedException ignored) {} }
        }
        if (last != null) { ss.close(); throw last; }
        server = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "ws-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        for (Client c : clients) c.close();
        clients.clear();
    }

    int clientCount() { return clients.size(); }

    // ---------------- stream state ----------------

    /** Call with false if audio capture is unavailable, so clients don't wait for it. */
    synchronized void setAudioExpected(boolean expected) {
        audioExpected = expected;
        if (!expected) { audioCodec = null; audioInitFrame = null; }
        broadcastConfig();
    }

    synchronized void setVideoInit(byte[] init, String codec) {
        videoCodec = codec;
        videoInitFrame = frame(0x2, typed(T_VIDEO_INIT, init));
        broadcastConfig();
    }

    synchronized void setAudioInit(byte[] init, String codec) {
        audioCodec = codec;
        audioInitFrame = frame(0x2, typed(T_AUDIO_INIT, init));
        broadcastConfig();
    }

    synchronized void sendVideo(byte[] fragment, boolean key) {
        if (clients.isEmpty() || videoInitFrame == null) return;
        byte[] f = frame(0x2, typed(T_VIDEO, fragment));
        for (Client c : clients) {
            if (!c.configured) continue;
            if (c.waitKey) { if (!key) continue; c.waitKey = false; }
            if (!c.offer(f)) recover(c);
        }
    }

    synchronized void sendAudio(byte[] fragment) {
        if (clients.isEmpty() || audioInitFrame == null) return;
        byte[] f = frame(0x2, typed(T_AUDIO, fragment));
        for (Client c : clients) {
            if (!c.configured || c.waitKey) continue; // start audio together with first video keyframe
            if (!c.offer(f)) recover(c);
        }
    }

    private boolean ready() {
        return videoInitFrame != null && (!audioExpected || audioInitFrame != null);
    }

    /** (Re)sends config + init segments; client rebuilds its MediaSource. */
    private void broadcastConfig() {
        if (!ready()) return;
        for (Client c : clients) configure(c);
        keyReq.requestKeyFrame();
    }

    private void configure(Client c) {
        c.queue.clear();
        String json = "{\"type\":\"config\",\"v\":\"" + videoCodec + "\",\"a\":"
                + (audioInitFrame != null ? "\"" + audioCodec + "\"" : "null") + "}";
        c.queue.offer(frame(0x1, json.getBytes(StandardCharsets.UTF_8)));
        c.queue.offer(videoInitFrame);
        if (audioInitFrame != null) c.queue.offer(audioInitFrame);
        c.waitKey = true;
        c.configured = true;
    }

    /** Slow client: drop backlog, restart from next keyframe. */
    private void recover(Client c) {
        long now = System.currentTimeMillis();
        if (now - c.lastRecoverMs < 5000) { c.close(); return; } // persistently stuck viewer
        c.lastRecoverMs = now;
        configure(c);
        keyReq.requestKeyFrame();
    }

    private synchronized void join(Client c) {
        if (!running || !c.open) { c.close(); return; }
        clients.add(c);
        if (!c.open) { clients.remove(c); return; } // closed while registering
        if (ready()) { configure(c); keyReq.requestKeyFrame(); }
    }

    private static byte[] typed(byte type, byte[] payload) {
        byte[] r = new byte[payload.length + 1];
        r[0] = type;
        System.arraycopy(payload, 0, r, 1, payload.length);
        return r;
    }

    /** Server->client WebSocket frame (unmasked, FIN). */
    static byte[] frame(int opcode, byte[] p) {
        int n = p.length;
        int h = n < 126 ? 2 : (n < 65536 ? 4 : 10);
        byte[] f = new byte[h + n];
        f[0] = (byte) (0x80 | opcode);
        if (n < 126) {
            f[1] = (byte) n;
        } else if (n < 65536) {
            f[1] = 126; f[2] = (byte) (n >>> 8); f[3] = (byte) n;
        } else {
            f[1] = 127;
            long L = n;
            for (int i = 0; i < 8; i++) f[2 + i] = (byte) (L >>> (56 - 8 * i));
        }
        System.arraycopy(p, 0, f, h, n);
        return f;
    }

    // ---------------- networking ----------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "http-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            if (!isLocal(s.getInetAddress())) { s.close(); return; } // never serve the internet side
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            String path = req.length > 1 ? req[1] : "/";
            Map<String, String> hdr = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) hdr.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            String host = hdr.get("host");
            if (!isIpHost(host)) { // DNS-rebinding guard: only http://<IP>:port is served
                respond(out, "403 Forbidden", "text/plain", "Forbidden".getBytes(StandardCharsets.US_ASCII));
                s.close();
                return;
            }
            if (path.startsWith("/ws") && "websocket".equalsIgnoreCase(hdr.get("upgrade")) && hdr.containsKey("sec-websocket-key")) {
                String origin = hdr.get("origin");
                if (origin != null && !origin.equals("http://" + host)) { // other web pages may not connect
                    respond(out, "403 Forbidden", "text/plain", "Forbidden".getBytes(StandardCharsets.US_ASCII));
                    s.close();
                    return;
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((hdr.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                s.setSoTimeout(15000); // browser answers our pings; silence = dead viewer
                Client c = new Client(s, in, out);
                c.startThreads();
                join(c);
                return;
            }
            if (path.equals("/") || path.startsWith("/?") || path.startsWith("/index.html")) {
                respond(out, "200 OK", "text/html; charset=utf-8", page);
            } else {
                respond(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.US_ASCII));
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /** Host header must be a literal IP (optionally with port), never a domain name. */
    static boolean isIpHost(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.startsWith("[")) {                       // [IPv6]:port
            int e = h.indexOf(']');
            if (e < 0) return false;
            String rest = h.substring(e + 1);
            if (!rest.isEmpty() && !rest.matches(":\\d{1,5}")) return false;
            return h.substring(1, e).matches("[0-9a-fA-F:.%a-zA-Z0-9]+") && h.substring(1, e).contains(":");
        }
        return h.matches("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(:\\d{1,5})?");
    }

    /** Private/link-local/loopback only (hotspot clients), IPv4 and IPv6. */
    static boolean isLocal(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) return (a.getAddress()[0] & 0xFE) == 0xFC; // fc00::/7 ULA
        return false;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 16384) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1
                  : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private final class Client {
        final Socket s; final InputStream in; final OutputStream out;
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        volatile boolean waitKey = true, configured = false, open = true;
        long lastRecoverMs;

        Client(Socket s, InputStream in, OutputStream out) { this.s = s; this.in = in; this.out = out; }

        boolean offer(byte[] f) {
            if (queue.size() >= QUEUE_LIMIT) return false;
            return queue.offer(f);
        }

        void startThreads() {
            Thread w = new Thread(() -> {
                try {
                    long lastPing = System.currentTimeMillis();
                    while (open) {
                        byte[] f = queue.poll(500, TimeUnit.MILLISECONDS);
                        if (f != null) out.write(f);
                        long now = System.currentTimeMillis();
                        if (now - lastPing >= 5000) { out.write(PING); lastPing = now; }
                    }
                } catch (Exception e) { /* fall through */ }
                close();
            }, "ws-writer");
            w.setDaemon(true);
            w.start();
            Thread r = new Thread(() -> {
                try { readFrames(); } catch (Exception ignored) {}
                close();
            }, "ws-reader");
            r.setDaemon(true);
            r.start();
        }

        /** Reads and discards client frames; returns on close/EOF. */
        private void readFrames() throws IOException {
            while (open) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                long len = b1 & 0x7F;
                if (len == 126) len = (rd(in) << 8) | rd(in);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | rd(in); }
                if ((b1 & 0x80) != 0) for (int i = 0; i < 4; i++) rd(in);
                for (long i = 0; i < len; i++) rd(in);
                if ((b0 & 0x0F) == 0x8) return; // close
            }
        }

        private int rd(InputStream in) throws IOException {
            int c = in.read();
            if (c < 0) throw new IOException("eof");
            return c;
        }

        void close() {
            if (!open) return;
            open = false;
            clients.remove(this);
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
