package com.fujixfer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.SocketFactory;

/**
 * Fujifilm's Wi-Fi transport: a PTP-like protocol on TCP 55740 (camera is at 192.168.0.1).
 *
 * Only the first packet looks like PTP/IP; after the handshake the framing is the same as USB bulk.
 * The handshake packet must be exactly 82 bytes, otherwise the camera (X-T4 at least) closes the socket.
 * While the user has not yet accepted the new device on the camera screen the camera answers INIT_FAIL;
 * we keep retrying until pairingWaitMs expires instead of giving up after the first answer.
 */
public final class FujiTcpLink implements PtpLink {
    public static final int DEFAULT_PORT = 55740;
    public static final String DEFAULT_HOST = "192.168.0.1";

    static final int INIT_COMMAND_REQUEST = 1;
    static final int INIT_COMMAND_ACK = 2;
    static final int INIT_FAIL = 5;
    static final int INIT_PACKET_SIZE = 82;
    static final long PROTOCOL_MAGIC = 0x8f53e4f2L;

    /**
     * The client id XApp puts in every PTP/IP init request, read from the init template in libFTLPTPIP.so
     * (offset 12 of the 82-byte packet; fudge sends the same bytes). Only the first 12 bytes are fixed:
     * MngPTP::Open overwrites bytes 24..27 with the phone's own IPv4 address on the camera network
     * (getsockname, written as a little-endian uint32, so 192.168.0.129 becomes 81 00 A8 C0). This
     * class does the same in {@link #buildInitRequest}; the last 4 bytes here are only the template.
     */
    public static final byte[] FUJI_CLIENT_GUID = {
        (byte) 0xad, (byte) 0xa5, 0x48, 0x5d, (byte) 0x87, (byte) 0xb2, 0x7f, 0x0b,
        (byte) 0xd3, (byte) 0xd5, (byte) 0xde, (byte) 0xd0, 0x00, 0x00, 0x00, 0x00,
    };

    public static final class Options {
        public int connectTimeoutMs = 5000;
        public int readTimeoutMs = 10000;
        /** How long to keep retrying while the camera waits for the user to accept the pairing. */
        public int pairingWaitMs = 40000;
        public int initRetryDelayMs = 500;
        /** Attempts for plain connection failures (unreachable, reset). Pairing waits are separate. */
        public int connectAttempts = 3;
        /**
         * Right after its access point comes up the camera refuses TCP for a while (seen on an X-M5:
         * ECONNREFUSED). Keep knocking every second for this long before calling it a failure.
         */
        public int serviceWaitMs = 20000;
        /**
         * Pause between two connection attempts while the camera refuses TCP (it is starting its transfer
         * service). Was 1 s; a refusal costs nothing on the camera side, so knocking more often only gets the
         * session open sooner once the service is up.
         */
        public int refusedRetryMs = 250;
        /**
         * How long to wait for the handshake reply on one socket before closing it and opening a new one.
         * XApp does exactly this: CameraConnectModel.cameraOpen calls Open with a 5 s timeout and, on
         * failure, opens a fresh socket, for 30 s in total. Waiting longer on a single socket (as we did,
         * 20 s) is what the X-M5 logs show failing: no answer, then the port closes.
         */
        public int handshakeTimeoutMs = 5000;
        /** Total time for fresh-socket attempts while the camera does not answer (XApp: 30 s). */
        public int openWaitMs = 30000;
        /** Called once when the camera has not answered the handshake after a few seconds. */
        public Runnable onWaitingForCamera;
        /** Diagnostic lines (init packet, reply type); may be null. */
        public java.util.function.Consumer<String> onTrace;
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String cameraName;
    private final SocketFactory factory;
    private final String host;
    private final Options options;
    private final List<Socket> sideSockets = new ArrayList<>();
    /** When > 0, reads wait up to this deadline instead of the normal read timeout (see expectSlowReply). */
    private volatile long patienceDeadline;
    private volatile Runnable onSlowReply;

    private FujiTcpLink(Socket socket, String cameraName, SocketFactory factory, String host, Options options)
            throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.cameraName = cameraName;
        this.factory = factory;
        this.host = host;
        this.options = options;
    }

    public String getCameraName() {
        return cameraName;
    }

    /**
     * @param sf   use Network.getSocketFactory() on Android so the traffic is forced onto the camera's Wi-Fi
     * @param guid 16 stable bytes identifying this phone. Keep the same GUID across runs: the camera
     *             remembers paired devices by GUID, a fresh one each time re-triggers the pairing prompt.
     */
    public static FujiTcpLink connect(SocketFactory sf, String host, int port, byte[] guid,
                                      String friendlyName, Options o) throws IOException {
        if (guid.length != 16) throw new IllegalArgumentException("guid must be 16 bytes");
        long start = System.currentTimeMillis();
        long deadline = start + o.pairingWaitMs;
        long serviceDeadline = start + o.serviceWaitMs;
        long openDeadline = start + o.openWaitMs;
        IOException last = null;
        int failures = 0;
        int refused = 0;
        int socketNo = 0;
        boolean[] told = {false}; // the waiting note is shown once, not once per socket
        while (true) {
            Socket s = null;
            try {
                s = sf.createSocket();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.setReceiveBufferSize(512 * 1024);
                try {
                    s.connect(new InetSocketAddress(host, port), o.connectTimeoutMs);
                } catch (ConnectException e) {
                    // nothing listening (yet, or again): the camera is (re)starting its transfer service
                    closeQuietly(s);
                    last = e;
                    refused++;
                    long now = System.currentTimeMillis();
                    if (now + o.refusedRetryMs >= Math.max(serviceDeadline, openDeadline)) break;
                    sleep(o.refusedRetryMs);
                    continue;
                }
                socketNo++;
                if (o.onTrace != null && refused > 0 && socketNo == 1) {
                    o.onTrace.accept("porta 55740 rifiutata " + refused + " volte, aperta dopo "
                            + (System.currentTimeMillis() - start) + " ms");
                }
                if (o.onTrace != null && socketNo > 1) o.onTrace.accept("nuovo socket n. " + socketNo);
                String name = handshake(s, guid, friendlyName, o, deadline, told);
                s.setSoTimeout(o.readTimeoutMs);
                return new FujiTcpLink(s, name, sf, host, o);
            } catch (AccessDenied e) {
                closeQuietly(s);
                throw e; // pairing window expired
            } catch (SocketTimeoutException e) {
                // no answer on this socket: like XApp, drop it and try a fresh one until openWaitMs
                closeQuietly(s);
                last = e;
                if (o.onTrace != null) o.onTrace.accept("nessuna risposta in " + o.handshakeTimeoutMs + " ms, chiudo il socket");
                if (System.currentTimeMillis() + o.initRetryDelayMs >= openDeadline) break;
                sleep(o.initRetryDelayMs);
            } catch (IOException e) {
                closeQuietly(s);
                last = e;
                if (o.onTrace != null) o.onTrace.accept("errore socket: " + e.getMessage());
                if (++failures >= o.connectAttempts
                        || System.currentTimeMillis() + o.initRetryDelayMs >= deadline) break;
                sleep(o.initRetryDelayMs);
            }
        }
        throw last != null ? last : new IOException("Cannot connect to camera");
    }

    /**
     * Handshake, then the Fuji session set-up ({@link FujiWifi#setup}): OpenSession, wait for the camera to
     * grant access, client mode and gallery configuration. Closes the socket if anything fails.
     */
    public static PtpSession openSession(SocketFactory sf, String host, int port, byte[] guid,
                                         String friendlyName, Options o) throws IOException {
        return openSession(sf, host, port, guid, friendlyName, o, new FujiWifi.Options(), null);
    }

    public static PtpSession openSession(SocketFactory sf, String host, int port, byte[] guid, String friendlyName,
                                         Options o, FujiWifi.Options fo, FujiWifi.Progress progress)
            throws IOException {
        FujiTcpLink link = connect(sf, host, port, guid, friendlyName, o);
        PtpSession s = new PtpSession(link);
        try {
            FujiWifi.setup(link, s, fo, progress);
            return s;
        } catch (IOException | RuntimeException e) {
            link.close();
            throw e;
        }
    }

    /**
     * The next reads may take up to totalMs (the camera can sit on a dialog before answering a mode
     * change). onSlow runs once if nothing arrived after a few seconds. Undo with {@link #endSlowReply}.
     */
    public void expectSlowReply(long totalMs, Runnable onSlow) {
        onSlowReply = onSlow;
        patienceDeadline = System.currentTimeMillis() + totalMs;
    }

    public void endSlowReply() {
        patienceDeadline = 0;
        onSlowReply = null;
    }

    /** Opens one of the camera's extra ports (55741/55742) and keeps it open until close(). */
    void openSidePort(int port) throws IOException {
        Socket s = factory.createSocket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), options.connectTimeoutMs);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
        synchronized (sideSockets) {
            sideSockets.add(s);
        }
    }

    /** The camera refused the pairing for the whole wait window. */
    public static final class AccessDenied extends IOException {
        private static final long serialVersionUID = 1L;
        AccessDenied() {
            super("Camera refused the connection (accept the new device on the camera screen)");
        }
    }

    private static String handshake(Socket s, byte[] guid, String friendlyName, Options o, long deadline,
                                    boolean[] told)
            throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        byte[] req = buildInitRequest(guid, friendlyName, s.getLocalAddress());
        if (o.onTrace != null) {
            o.onTrace.accept("init " + s.getLocalAddress().getHostAddress() + " -> " + s.getInetAddress().getHostAddress()
                    + ":" + s.getPort() + ", byte 12-39: " + hex(req, 12, 28));
        }
        while (true) {
            out.write(req);
            out.flush();
            byte[] hdr = new byte[8];
            readReply(s, in, hdr, o, told);
            long len = Ptp.u32(hdr, 0);
            long type = Ptp.u32(hdr, 4);
            if (len < 8 || len > 4096) throw new IOException("Bad init reply length " + len);
            byte[] body = new byte[(int) len - 8];
            readFully(in, body, 0, body.length);
            if (o.onTrace != null) o.onTrace.accept("risposta init: tipo " + type + ", " + len + " byte");
            if (type == INIT_COMMAND_ACK) {
                return body.length > 20 ? decodeName(body, 20) : "";
            }
            if (type == INIT_FAIL) {
                if (System.currentTimeMillis() + o.initRetryDelayMs >= deadline) throw new AccessDenied();
                sleep(o.initRetryDelayMs);
                continue; // same socket, camera may still be waiting for the user
            }
            throw new IOException("Unexpected init reply type " + type);
        }
    }

    /**
     * Reads the handshake reply header, waiting up to handshakeTimeoutMs. The camera is on its
     * "waiting for a device" screen; it answers on its own once the init is accepted. Polls in short
     * steps so the caller can show a progress note once.
     */
    private static void readReply(Socket s, InputStream in, byte[] hdr, Options o, boolean[] told) throws IOException {
        long end = System.currentTimeMillis() + o.handshakeTimeoutMs;
        int step = Math.max(200, Math.min(Math.min(o.readTimeoutMs, 3000), o.handshakeTimeoutMs));
        int got = 0;
        s.setSoTimeout(step);
        while (got < hdr.length) {
            try {
                int n = in.read(hdr, got, hdr.length - got);
                if (n < 0) throw new SocketException("Camera closed the connection");
                got += n;
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() >= end) {
                    throw new SocketTimeoutException("La camera non ha risposto all'apertura della sessione");
                }
                if (!told[0] && o.onWaitingForCamera != null) {
                    told[0] = true;
                    o.onWaitingForCamera.run();
                }
            }
        }
        s.setSoTimeout(o.readTimeoutMs);
    }

    /**
     * The 82-byte init exactly as XApp's MngPTP::Open builds it: template header and client id, the phone's
     * IPv4 address at 24..27 (little-endian uint32 of the address), then the name as one byte per UTF-16
     * unit (XApp copies the low byte of each char, so only ASCII survives; we replace others with '_').
     */
    static byte[] buildInitRequest(byte[] guid, String friendlyName, java.net.InetAddress local) {
        byte[] p = new byte[INIT_PACKET_SIZE];
        Ptp.putU32(p, 0, INIT_PACKET_SIZE);
        Ptp.putU32(p, 4, INIT_COMMAND_REQUEST);
        Ptp.putU32(p, 8, PROTOCOL_MAGIC);
        System.arraycopy(guid, 0, p, 12, 16);
        byte[] ip = local == null ? null : local.getAddress();
        if (ip != null && ip.length == 4) {
            for (int i = 0; i < 4; i++) p[24 + i] = ip[3 - i]; // 192.168.0.129 -> 81 00 A8 C0
        }
        int maxChars = (INIT_PACKET_SIZE - 28) / 2 - 1; // 26 chars + terminator
        for (int i = 0; i < friendlyName.length() && i < maxChars; i++) {
            char ch = friendlyName.charAt(i);
            p[28 + 2 * i] = (byte) (ch >= 0x20 && ch < 0x7F ? ch : '_');
        }
        return p;
    }

    private static String hex(byte[] b, int off, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + n; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }

    private static String decodeName(byte[] body, int off) {
        int end = off;
        while (end + 1 < body.length && (body[end] != 0 || body[end + 1] != 0)) end += 2;
        return new String(body, off, end - off, StandardCharsets.UTF_16LE);
    }

    // ------------------------------------------------------------------ PtpLink

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        out.write(buf, off, len);
        out.flush();
    }

    @Override
    public void readFully(byte[] buf, int off, int len) throws IOException {
        if (patienceDeadline <= 0) {
            readFully(in, buf, off, len);
            return;
        }
        boolean told = false;
        socket.setSoTimeout(Math.min(options.readTimeoutMs, 3000));
        try {
            while (len > 0) {
                try {
                    int n = in.read(buf, off, len);
                    if (n < 0) throw new SocketException("Camera closed the connection");
                    off += n;
                    len -= n;
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() >= patienceDeadline) throw e;
                    Runnable r = onSlowReply;
                    if (!told && r != null) {
                        told = true;
                        r.run();
                    }
                }
            }
        } finally {
            socket.setSoTimeout(options.readTimeoutMs);
        }
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n < 0) throw new SocketException("Camera closed the connection");
            off += n;
            len -= n;
        }
    }

    @Override
    public void close() {
        synchronized (sideSockets) {
            for (Socket s : sideSockets) closeQuietly(s);
            sideSockets.clear();
        }
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
