package com.fujixfer.core;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** In-process imitation of a Fuji body on the TCP command pipe, with fault injection. */
final class FakeFujiCamera implements Closeable {
    static final class Obj {
        final String name;
        final byte[] data;
        final int format;
        final long reportedSize;

        /** Total size when the object is virtual (generated on the fly, no memory); 0 for real data. */
        long virtualSize = 0;

        /** Reduced copy handed out in mode 0xD226 = 1 (XApp's zoom view); null = this body has none for it. */
        byte[] small;

        Obj(String name, int format, byte[] data, long reportedSize) {
            this.name = name;
            this.format = format;
            this.data = data;
            this.reportedSize = reportedSize;
        }

        long size() {
            return virtualSize > 0 ? virtualSize : data.length;
        }

        void fill(long off, int n, byte[] dst) {
            if (virtualSize == 0) {
                System.arraycopy(data, (int) off, dst, 0, n);
            } else {
                for (int i = 0; i < n; i++) dst[i] = virtualByte(off + i);
            }
        }

        static byte virtualByte(long pos) {
            return (byte) (pos * 31 + (pos >>> 8) + (pos >>> 29));
        }
    }

    final List<Obj> objects = new ArrayList<>();
    volatile int initFailuresRemaining = 0;
    /** The first N sockets get the init but never an answer (what the X-M5 logs look like). */
    volatile int silentSockets = 0;
    /** Drop the connection once, after this many GetObjectInfo calls (0 = never). */
    volatile int dropAfterObjectInfos = 0;
    /** X-M5 suspicion: GetObjectInfo is slow in real-size mode, and plain mode already reports real sizes. */
    volatile long realModeInfoDelayMs = 0;
    volatile boolean plainSizesReal = false;
    /** A body that hangs when a second command is already queued (to test the fallback). */
    volatile boolean rejectPipelining = false;
    /** Like the X-M5: of several queued GetObjectInfo only the last one is answered, the others vanish. */
    volatile boolean dropQueued = false;
    /** Like the X-M5 on videos: GetPartialObject sends at most this many bytes (0 = all that was asked). */
    volatile int partialCap = 0;
    final AtomicInteger queuedCommandsSeen = new AtomicInteger();
    /** Hold the handshake reply this long, like a body waiting for OK on its display. */
    volatile long ackDelayMs = 0;
    volatile int dropsRemaining = 0;
    volatile int stallsRemaining = 0;
    volatile long faultAfterDataBytes = 4 * 1024 * 1024;
    volatile boolean partialSupported = true;
    volatile boolean emptyDeviceInfo = true;
    volatile boolean shuffleHandlesPerConnection = false;

    final AtomicLong dataBytesSent = new AtomicLong();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger badInitPackets = new AtomicInteger();
    final AtomicInteger extPartialCalls = new AtomicInteger();
    final AtomicInteger stdPartialCalls = new AtomicInteger();
    volatile boolean standardPartialRejects64Bit = true;
    final AtomicInteger initFailsSent = new AtomicInteger();
    volatile byte[] lastGuid;
    /** Thumbnail JPEG per file name; files without an entry answer NoThumbnailPresent. */
    final java.util.Map<String, byte[]> thumbs = new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean extThumbSupported = true;
    final AtomicInteger thumbCalls = new AtomicInteger();
    final AtomicInteger extThumbCalls = new AtomicInteger();
    final AtomicInteger handleListings = new AtomicInteger();

    // ---- Fuji Wi-Fi behaviour (what fudge and XApp do; see FujiWifi). plainPtp = old generic PTP fake.
    volatile boolean plainPtp = false;
    /** DF24. >= 0x20007 makes a Bluetooth hand-off use the XApp mode (ClientState 20). */
    volatile long remoteVersion = 0x2000BL;
    /** Number of event polls per connection that still report CameraState 0 (waiting for access). */
    volatile int accessPolls = 0;
    volatile int cameraStateAfterAccess = 6;
    /** Hold the ClientState reply this long (the camera may show a dialog first). */
    volatile long clientStateDelayMs = 0;
    /** ObjectInfo size before 0xD226 = 2 / 0xD227 = 1, like the 100 kB placeholder fudge describes. */
    static final long PLACEHOLDER_SIZE = 100_000;
    final AtomicInteger getDeviceInfoCalls = new AtomicInteger();
    final AtomicInteger objectInfoCalls = new AtomicInteger();
    final AtomicInteger ignoredBeforeSetup = new AtomicInteger();
    final AtomicInteger downloadsNotOriginal = new AtomicInteger();
    final AtomicInteger thumbsWithoutInfo = new AtomicInteger();
    final AtomicInteger openCaptures = new AtomicInteger();
    final AtomicInteger sidePortConnections = new AtomicInteger();
    volatile long firstOpenSessionTid = -1;
    /** Commands in order, e.g. "1016 DF01=20", "1002 tid=1". */
    final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
    private ServerSocket eventServer;
    private ServerSocket liveServer;

    private final ServerSocket server;
    private volatile boolean closed;

    FakeFujiCamera() throws IOException {
        this(0);
    }

    FakeFujiCamera(int port) throws IOException {
        server = new ServerSocket(port);
        Thread t = new Thread(this::acceptLoop, "fake-fuji-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    static byte[] pattern(int size, int seed) {
        byte[] b = new byte[size];
        long x = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < size; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            b[i] = (byte) x;
        }
        return b;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                int idx = connections.incrementAndGet();
                Thread t = new Thread(() -> serve(s, idx), "fake-fuji-conn-" + idx);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private long handleOf(int objectIndex, int connIdx) {
        if (!plainPtp) { // Wi-Fi: 1..n; "shuffle" rotates the numbering like a new shot shifting older ones
            int n = objects.size();
            int shift = shuffleHandlesPerConnection ? (connIdx - 1) % n : 0;
            return 1 + (objectIndex + shift) % n;
        }
        return 100 + objectIndex + (shuffleHandlesPerConnection ? 1000L * (connIdx - 1) : 0);
    }

    private int indexOf(long handle, int connIdx) {
        if (!plainPtp) {
            int n = objects.size();
            if (handle < 1 || handle > n) return -1;
            int shift = shuffleHandlesPerConnection ? (connIdx - 1) % n : 0;
            return (int) (((handle - 1 - shift) % n + n) % n);
        }
        long base = handle - 100 - (shuffleHandlesPerConnection ? 1000L * (connIdx - 1) : 0);
        return base >= 0 && base < objects.size() ? (int) base : -1;
    }

    /** Listens on two extra ports standing in for 55741/55742; returns {event, liveview}. */
    int[] openSidePorts() throws IOException {
        eventServer = new ServerSocket(0);
        liveServer = new ServerSocket(0);
        for (ServerSocket ss : new ServerSocket[]{eventServer, liveServer}) {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Socket c = ss.accept();
                        sidePortConnections.incrementAndGet();
                        c.setSoTimeout(0);
                    }
                } catch (IOException ignored) {
                }
            }, "fake-fuji-side");
            t.setDaemon(true);
            t.start();
        }
        return new int[]{eventServer.getLocalPort(), liveServer.getLocalPort()};
    }

    private void serve(Socket s, int connIdx) {
        long sentThisConn = 0;
        try {
            s.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            // ---- handshake
            while (true) {
                byte[] first = new byte[4];
                in.readFully(first);
                long len = Ptp.u32(first, 0);
                if (len != 82) { // the real X-T4 just closes on any other length
                    badInitPackets.incrementAndGet();
                    s.close();
                    return;
                }
                byte[] rest = new byte[(int) len - 4];
                in.readFully(rest);
                if (Ptp.u32(rest, 0) != 1 || Ptp.u32(rest, 4) != 0x8f53e4f2L) {
                    badInitPackets.incrementAndGet();
                    s.close();
                    return;
                }
                byte[] g = new byte[16];
                System.arraycopy(rest, 8, g, 0, 16);
                lastGuid = g;
                if (initFailuresRemaining > 0) {
                    initFailuresRemaining--;
                    initFailsSent.incrementAndGet();
                    byte[] fail = new byte[8];
                    Ptp.putU32(fail, 0, 8);
                    Ptp.putU32(fail, 4, 5);
                    out.write(fail);
                    continue;
                }
                if (silentSockets > 0) {
                    silentSockets--;
                    while (in.read() >= 0) {
                        // say nothing until the client gives up and closes
                    }
                    s.close();
                    return;
                }
                if (ackDelayMs > 0) Thread.sleep(ackDelayMs);
                byte[] name = "FakeX-T4\0".getBytes(StandardCharsets.UTF_16LE);
                byte[] ack = new byte[8 + 4 + 16 + name.length];
                Ptp.putU32(ack, 0, ack.length);
                Ptp.putU32(ack, 4, 2);
                System.arraycopy(g, 0, ack, 12, 16);
                System.arraycopy(name, 0, ack, 28, name.length);
                out.write(ack);
                break;
            }

            // ---- PTP loop
            int clientState = 0;
            int polls = 0;
            int compressSmall = 0;
            int correctSize = 0;
            long lastInfoHandle = -1;
            while (true) {
                byte[] hdr = new byte[12];
                in.readFully(hdr);
                long len = Ptp.u32(hdr, 0);
                int type = Ptp.u16(hdr, 4);
                int op = Ptp.u16(hdr, 6);
                long tid = Ptp.u32(hdr, 8);
                byte[] pb = new byte[(int) len - 12];
                in.readFully(pb);
                long[] p = new long[pb.length / 4];
                for (int i = 0; i < p.length; i++) p[i] = Ptp.u32(pb, 4 * i);
                if (type != Ptp.TYPE_COMMAND) continue;
                if (in.available() > 0 && op == Ptp.OC_GET_OBJECT_INFO) {
                    queuedCommandsSeen.incrementAndGet();
                    if (dropQueued) continue;
                    if (rejectPipelining) {
                        while (in.read() >= 0) {
                            // hang until the client gives up
                        }
                        s.close();
                        return;
                    }
                }
                long infoBefore = lastInfoHandle;
                lastInfoHandle = -1;

                if (!plainPtp) {
                    boolean objectOp = op == Ptp.OC_GET_OBJECT_INFO || op == Ptp.OC_GET_THUMB
                            || op == Ptp.OC_GET_PARTIAL_OBJECT || op == Ptp.OC_GET_OBJECT
                            || op == Ptp.OC_FUJI_GET_EXT_PARTIAL_OBJECT || op == Ptp.OC_GET_OBJECT_PROP_VALUE
                            || op == Ptp.OC_GET_OBJECT_HANDLES || op == Ptp.OC_GET_STORAGE_IDS;
                    if (objectOp && clientState == 0) {
                        // like the X-M5: nothing happens until the client picked a function; the body
                        // later gives up with "not found, please select the function again"
                        ignoredBeforeSetup.incrementAndGet();
                        log.add(String.format("%04X ignored", op));
                        continue;
                    }
                    if (op == Ptp.OC_GET_OBJECT_HANDLES || op == Ptp.OC_GET_STORAGE_IDS) {
                        respond(out, tid, Ptp.RC_OPERATION_NOT_SUPPORTED);
                        continue;
                    }
                    if (op == Ptp.OC_GET_DEVICE_PROP_VALUE) {
                        int code = (int) p[0];
                        log.add(String.format("1015 %04X", code));
                        byte[] v;
                        if (code == FujiWifi.DPC_EVENTS) {
                            polls++;
                            int st = polls <= accessPolls ? 0 : cameraStateAfterAccess;
                            v = new byte[2 + 6 * 2];
                            Ptp.putU16(v, 0, 2);
                            Ptp.putU16(v, 2, FujiWifi.DPC_CAMERA_STATE);
                            Ptp.putU32(v, 4, st);
                            Ptp.putU16(v, 8, FujiWifi.DPC_OBJECT_COUNT);
                            Ptp.putU32(v, 10, objects.size());
                        } else {
                            long val;
                            switch (code) {
                                case FujiWifi.DPC_IMAGE_GET_VERSION: val = 4; break;
                                case FujiWifi.DPC_GET_OBJECT_VERSION: val = 5; break;
                                case FujiWifi.DPC_REMOTE_VERSION: val = remoteVersion; break;
                                case FujiWifi.DPC_REMOTE_GET_OBJECT_VERSION: val = 5; break;
                                case FujiWifi.DPC_PHOTO_VIEW_EX_VERSION: val = 3; break;
                                case FujiWifi.DPC_OBJECT_COUNT: val = objects.size(); break;
                                case FujiWifi.DPC_UNKNOWN_D22B:
                                case FujiWifi.DPC_IMPORT_OBJECT_COUNT:
                                case FujiWifi.DPC_IMPORT_OBJECT_HANDLES: val = 0; break;
                                default: val = -1;
                            }
                            if (val < 0) {
                                respond(out, tid, 0x200A); // DevicePropNotSupported
                                continue;
                            }
                            v = new byte[4];
                            Ptp.putU32(v, 0, val);
                        }
                        sendData(out, tid, op, v);
                        respond(out, tid, Ptp.RC_OK);
                        continue;
                    }
                    if (op == Ptp.OC_SET_DEVICE_PROP_VALUE) {
                        byte[] dh = new byte[12];
                        in.readFully(dh);
                        byte[] dv = new byte[(int) Ptp.u32(dh, 0) - 12];
                        in.readFully(dv);
                        long val = dv.length == 2 ? Ptp.u16(dv, 0) : Ptp.u32(dv, 0);
                        int code = (int) p[0];
                        log.add(String.format("1016 %04X=%d", code, val));
                        if (code == FujiWifi.DPC_CLIENT_STATE) {
                            if (clientStateDelayMs > 0) Thread.sleep(clientStateDelayMs);
                            clientState = (int) val;
                        } else if (code == FujiWifi.DPC_COMPRESS_SMALL) {
                            compressSmall = (int) val;
                        } else if (code == FujiWifi.DPC_CORRECT_FILE_SIZE) {
                            correctSize = (int) val;
                        }
                        respond(out, tid, Ptp.RC_OK);
                        continue;
                    }
                    if (op == Ptp.OC_INITIATE_OPEN_CAPTURE || op == Ptp.OC_TERMINATE_OPEN_CAPTURE) {
                        log.add(String.format("%04X", op) + (op == Ptp.OC_TERMINATE_OPEN_CAPTURE ? " tid=" + p[0] : " at " + tid));
                        if (op == Ptp.OC_INITIATE_OPEN_CAPTURE) openCaptures.incrementAndGet();
                        respond(out, tid, Ptp.RC_OK);
                        continue;
                    }
                    if (op == Ptp.OC_GET_THUMB && remoteVersion > 0x20006L && infoBefore != p[0]) {
                        thumbsWithoutInfo.incrementAndGet(); // fudge: GetThumb blocks forever on these bodies
                        continue;
                    }
                    boolean original = remoteVersion >= FujiWifi.REMOTE_VERSION_XAPP_MIN && clientState == 20
                            ? compressSmall == 2 : correctSize == 1;
                    if (op == Ptp.OC_GET_OBJECT_INFO) {
                        int calls = objectInfoCalls.incrementAndGet();
                        if (dropAfterObjectInfos > 0 && calls > dropAfterObjectInfos) {
                            dropAfterObjectInfos = 0;
                            s.setSoLinger(true, 0);
                            s.close();
                            return;
                        }
                        int i = indexOf(p[0], connIdx);
                        if (i < 0) {
                            respond(out, tid, Ptp.RC_INVALID_OBJECT_HANDLE);
                        } else {
                            if (original && realModeInfoDelayMs > 0) Thread.sleep(realModeInfoDelayMs);
                            Obj o = objects.get(i);
                            long sz = original || plainSizesReal ? -1 : PLACEHOLDER_SIZE;
                            if (compressSmall == 1 && clientState == 20 && o.small != null) sz = o.small.length;
                            sendData(out, tid, op, objectInfo(o, sz));
                            respond(out, tid, Ptp.RC_OK);
                            lastInfoHandle = p[0];
                        }
                        continue;
                    }
                    if ((op == Ptp.OC_GET_PARTIAL_OBJECT || op == Ptp.OC_FUJI_GET_EXT_PARTIAL_OBJECT
                            || op == Ptp.OC_GET_OBJECT) && !original) {
                        downloadsNotOriginal.incrementAndGet();
                    }
                }

                switch (op) {
                    case Ptp.OC_OPEN_SESSION:
                        log.add("1002 tid=" + tid);
                        if (firstOpenSessionTid < 0) firstOpenSessionTid = tid;
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    case Ptp.OC_CLOSE_SESSION:
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    case Ptp.OC_GET_DEVICE_INFO:
                        getDeviceInfoCalls.incrementAndGet();
                        log.add("1001");
                        sendData(out, tid, op, emptyDeviceInfo ? new byte[0] : deviceInfo());
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    case Ptp.OC_GET_STORAGE_IDS:
                        sendData(out, tid, op, u32Array(0x00010001L));
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    case Ptp.OC_GET_THUMB:
                    case Ptp.OC_FUJI_GET_EXT_THUMB: {
                        if (op == Ptp.OC_FUJI_GET_EXT_THUMB) {
                            extThumbCalls.incrementAndGet();
                            if (!extThumbSupported) {
                                respond(out, tid, Ptp.RC_OPERATION_NOT_SUPPORTED);
                                break;
                            }
                        } else {
                            thumbCalls.incrementAndGet();
                        }
                        int i = indexOf(p[0], connIdx);
                        byte[] t = i < 0 ? null : thumbs.get(objects.get(i).name);
                        if (i < 0) {
                            respond(out, tid, Ptp.RC_INVALID_OBJECT_HANDLE);
                        } else if (t == null) {
                            respond(out, tid, Ptp.RC_NO_THUMBNAIL_PRESENT);
                        } else {
                            sendData(out, tid, op, t);
                            respond(out, tid, Ptp.RC_OK);
                        }
                        break;
                    }
                    case Ptp.OC_GET_OBJECT_HANDLES: {
                        handleListings.incrementAndGet();
                        long[] hs = new long[objects.size()];
                        for (int i = 0; i < hs.length; i++) hs[i] = handleOf(i, connIdx);
                        sendData(out, tid, op, u32Array(hs));
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    }
                    case Ptp.OC_GET_OBJECT_INFO: {
                        int i = indexOf(p[0], connIdx);
                        if (i < 0) {
                            respond(out, tid, Ptp.RC_INVALID_OBJECT_HANDLE);
                        } else {
                            sendData(out, tid, op, objectInfo(objects.get(i), -1));
                            respond(out, tid, Ptp.RC_OK);
                        }
                        break;
                    }
                    case Ptp.OC_GET_OBJECT_PROP_VALUE: {
                        int i = indexOf(p[0], connIdx);
                        if (i < 0) {
                            respond(out, tid, Ptp.RC_INVALID_OBJECT_HANDLE);
                        } else if (p[1] != Ptp.OPC_OBJECT_SIZE) {
                            respond(out, tid, Ptp.RC_PARAMETER_NOT_SUPPORTED);
                        } else {
                            byte[] v = new byte[8];
                            long sz = objects.get(i).size();
                            Ptp.putU32(v, 0, sz & 0xFFFFFFFFL);
                            Ptp.putU32(v, 4, sz >>> 32);
                            sendData(out, tid, op, v);
                            respond(out, tid, Ptp.RC_OK);
                        }
                        break;
                    }
                    case Ptp.OC_FUJI_GET_EXT_PARTIAL_OBJECT:
                    case Ptp.OC_GET_PARTIAL_OBJECT:
                    case Ptp.OC_GET_OBJECT: {
                        if (op == Ptp.OC_GET_PARTIAL_OBJECT && !partialSupported) {
                            respond(out, tid, Ptp.RC_OPERATION_NOT_SUPPORTED);
                            break;
                        }
                        int i = indexOf(p[0], connIdx);
                        if (i < 0) {
                            respond(out, tid, Ptp.RC_INVALID_OBJECT_HANDLE);
                            break;
                        }
                        Obj obj = objects.get(i);
                        if (!plainPtp && compressSmall == 1 && clientState == 20 && obj.small != null) {
                            obj = new Obj(obj.name, obj.format, obj.small, obj.small.length); // the S copy
                        }
                        long off;
                        long want;
                        if (op == Ptp.OC_FUJI_GET_EXT_PARTIAL_OBJECT) {
                            extPartialCalls.incrementAndGet();
                            off = (p[3] << 32) | p[1]; // {handle, offLow, length, offHigh}
                            want = p[2];
                        } else if (op == Ptp.OC_GET_PARTIAL_OBJECT) {
                            stdPartialCalls.incrementAndGet();
                            off = p[1];
                            want = p[2];
                            if (standardPartialRejects64Bit && off + want > 0xFFFFFFFFL) {
                                respond(out, tid, Ptp.RC_PARAMETER_NOT_SUPPORTED);
                                break;
                            }
                        } else {
                            off = 0;
                            want = obj.size();
                        }
                        int n = (int) Math.min(want, obj.size() - off);
                        if (partialCap > 0 && op != Ptp.OC_GET_OBJECT) n = Math.min(n, partialCap);
                        byte[] h = new byte[12];
                        Ptp.putU32(h, 0, 12 + n);
                        Ptp.putU16(h, 4, Ptp.TYPE_DATA);
                        Ptp.putU16(h, 6, op);
                        Ptp.putU32(h, 8, tid);
                        out.write(h);
                        byte[] buf = new byte[64 * 1024];
                        int pos = 0;
                        while (pos < n) {
                            int step = Math.min(buf.length, n - pos);
                            if (dropsRemaining > 0 && sentThisConn + step > faultAfterDataBytes) {
                                dropsRemaining--;
                                int part = (int) Math.max(0, faultAfterDataBytes - sentThisConn);
                                obj.fill(off + pos, part, buf);
                                out.write(buf, 0, part);
                                dataBytesSent.addAndGet(part);
                                s.setSoLinger(true, 0); // RST like a real Wi-Fi drop
                                s.close();
                                return;
                            }
                            if (stallsRemaining > 0 && sentThisConn + step > faultAfterDataBytes) {
                                stallsRemaining--;
                                Thread.sleep(8000); // far longer than the client's read timeout
                                s.close();
                                return;
                            }
                            obj.fill(off + pos, step, buf);
                            out.write(buf, 0, step);
                            dataBytesSent.addAndGet(step);
                            sentThisConn += step;
                            pos += step;
                        }
                        respond(out, tid, Ptp.RC_OK);
                        break;
                    }
                    default:
                        respond(out, tid, Ptp.RC_OPERATION_NOT_SUPPORTED);
                }
            }
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void respond(OutputStream out, long tid, int rc) throws IOException {
        byte[] r = new byte[12];
        Ptp.putU32(r, 0, 12);
        Ptp.putU16(r, 4, Ptp.TYPE_RESPONSE);
        Ptp.putU16(r, 6, rc);
        Ptp.putU32(r, 8, tid);
        out.write(r);
    }

    private static void sendData(OutputStream out, long tid, int op, byte[] payload) throws IOException {
        byte[] h = new byte[12 + payload.length];
        Ptp.putU32(h, 0, h.length);
        Ptp.putU16(h, 4, Ptp.TYPE_DATA);
        Ptp.putU16(h, 6, op);
        Ptp.putU32(h, 8, tid);
        System.arraycopy(payload, 0, h, 12, payload.length);
        out.write(h);
    }

    private static byte[] u32Array(long... v) {
        byte[] b = new byte[4 + 4 * v.length];
        Ptp.putU32(b, 0, v.length);
        for (int i = 0; i < v.length; i++) Ptp.putU32(b, 4 + 4 * i, v[i]);
        return b;
    }

    private static byte[] ptpString(String s) {
        if (s.isEmpty()) return new byte[]{0};
        byte[] u = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
        byte[] b = new byte[1 + u.length];
        b[0] = (byte) (s.length() + 1);
        System.arraycopy(u, 0, b, 1, u.length);
        return b;
    }

    private static byte[] deviceInfo() {
        byte[] vendor = ptpString("fujifilm.co.jp: 1.0;");
        int[] ops = {Ptp.OC_GET_DEVICE_INFO, Ptp.OC_OPEN_SESSION, Ptp.OC_CLOSE_SESSION, Ptp.OC_GET_STORAGE_IDS,
                Ptp.OC_GET_OBJECT_HANDLES, Ptp.OC_GET_OBJECT_INFO, Ptp.OC_GET_OBJECT, Ptp.OC_GET_PARTIAL_OBJECT};
        byte[] b = new byte[8 + vendor.length + 2 + 4 + ops.length * 2 + 16];
        int pos = 8;
        System.arraycopy(vendor, 0, b, pos, vendor.length);
        pos += vendor.length + 2;
        Ptp.putU32(b, pos, ops.length);
        pos += 4;
        for (int op : ops) {
            Ptp.putU16(b, pos, op);
            pos += 2;
        }
        return b;
    }

    private static byte[] objectInfo(Obj o, long sizeOverride) {
        byte[] name = ptpString(o.name);
        byte[] date = ptpString("20261030T194500");
        byte[] b = new byte[52 + name.length + date.length + 2];
        Ptp.putU32(b, 0, 0x00010001L);
        Ptp.putU16(b, 4, o.format);
        Ptp.putU32(b, 8, sizeOverride >= 0 ? sizeOverride : o.reportedSize);
        Ptp.putU32(b, 26, 6000);
        Ptp.putU32(b, 30, 4000);
        System.arraycopy(name, 0, b, 52, name.length);
        System.arraycopy(date, 0, b, 52 + name.length, date.length);
        return b;
    }

    @Override
    public void close() {
        closed = true;
        for (ServerSocket ss : new ServerSocket[]{server, eventServer, liveServer}) {
            if (ss == null) continue;
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }
}
