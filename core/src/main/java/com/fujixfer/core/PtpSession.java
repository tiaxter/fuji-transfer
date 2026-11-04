package com.fujixfer.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A PTP session over any {@link PtpLink}. Not thread safe beyond per-call synchronisation. */
public final class PtpSession implements Closeable {
    private static final int MAX_BUFFERED_DATA = 16 * 1024 * 1024;

    public static final class Response {
        public final int code;
        public final long[] params;
        public final byte[] data;

        Response(int code, long[] params, byte[] data) {
            this.code = code;
            this.params = params;
            this.data = data;
        }
    }

    private final PtpLink link;
    private int transactionId = 0;
    private boolean open;
    private Set<Integer> supportedOps; // null = unknown (some Fuji bodies return an empty DeviceInfo)
    /** Set when this is a Fuji Wi-Fi session configured by {@link FujiWifi}; changes listing and downloads. */
    private FujiWifi fujiWifi;

    public PtpSession(PtpLink link) {
        this.link = link;
    }

    /**
     * Detailed trace of every transaction (opcode, parameters, value sent, answer, time, bytes), for the app's
     * log file. Null = off, nothing is formatted.
     */
    public static volatile java.util.function.Consumer<String> wire;

    // ------------------------------------------------------------------ core transaction

    /**
     * Runs one PTP transaction. If sink is non-null the data phase is streamed into it as it
     * arrives (so a broken connection still leaves a valid prefix); otherwise the data phase is
     * buffered into Response.data.
     */
    public synchronized Response transact(int opcode, long[] params, OutputStream sink) throws IOException {
        return transact(opcode, params, null, sink);
    }

    /**
     * Same, with an optional host-to-camera data phase (SetDevicePropValue). The command and the data
     * container go out as two writes, like fudge does on Fuji's TCP pipe.
     */
    public synchronized Response transact(int opcode, long[] params, byte[] dataOut, OutputStream sink)
            throws IOException {
        java.util.function.Consumer<String> w = wire;
        if (w == null) return transactOnce(opcode, params, dataOut, sink);
        long t0 = System.nanoTime();
        long[] count = {0};
        OutputStream counted = sink == null ? null : new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                sink.write(b);
                count[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                sink.write(b, off, len);
                count[0] += len;
            }
        };
        String what = describe(opcode, params, dataOut, transactionId + 1);
        try {
            Response r = transactOnce(opcode, params, dataOut, counted);
            long bytes = sink == null ? r.data.length : count[0];
            w.accept(what + " -> " + Ptp.rcName(r.code) + " in " + ms(t0) + " ms" + (bytes > 0 ? ", " + bytes + " B" : ""));
            return r;
        } catch (IOException | RuntimeException e) {
            w.accept(what + " -> ERRORE dopo " + ms(t0) + " ms" + (count[0] > 0 ? " (" + count[0] + " B ricevuti)" : "")
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private static String ms(long t0) {
        return String.valueOf((System.nanoTime() - t0) / 1_000_000);
    }

    /** "1016 D226=2 [t42]", "101B 0x1f4, 0x0, 0x100000 [t43]". */
    private static String describe(int opcode, long[] params, byte[] dataOut, long tid) {
        StringBuilder b = new StringBuilder(String.format("%04X", opcode));
        if (opcode == Ptp.OC_SET_DEVICE_PROP_VALUE && params != null && params.length > 0 && dataOut != null) {
            long v = dataOut.length == 2 ? Ptp.u16(dataOut, 0) : dataOut.length >= 4 ? Ptp.u32(dataOut, 0) : -1;
            b.append(' ').append(String.format("%04X", params[0])).append('=').append(v);
        } else if (params != null) {
            for (int i = 0; i < params.length; i++) b.append(i == 0 ? " " : ", ").append("0x").append(Long.toHexString(params[i]));
        }
        return b.append(" [t").append(tid).append(']').toString();
    }

    private Response transactOnce(int opcode, long[] params, byte[] dataOut, OutputStream sink) throws IOException {
        int tid = ++transactionId;
        int n = params == null ? 0 : params.length;
        byte[] cmd = new byte[Ptp.HEADER_SIZE + 4 * n];
        Ptp.putU32(cmd, 0, cmd.length);
        Ptp.putU16(cmd, 4, Ptp.TYPE_COMMAND);
        Ptp.putU16(cmd, 6, opcode);
        Ptp.putU32(cmd, 8, tid);
        for (int i = 0; i < n; i++) Ptp.putU32(cmd, Ptp.HEADER_SIZE + 4 * i, params[i]);
        link.write(cmd, 0, cmd.length);
        if (dataOut != null) {
            byte[] data = new byte[Ptp.HEADER_SIZE + dataOut.length];
            Ptp.putU32(data, 0, data.length);
            Ptp.putU16(data, 4, Ptp.TYPE_DATA);
            Ptp.putU16(data, 6, opcode);
            Ptp.putU32(data, 8, tid);
            System.arraycopy(dataOut, 0, data, Ptp.HEADER_SIZE, dataOut.length);
            link.write(data, 0, data.length);
        }

        ByteArrayOutputStream buffered = null;
        byte[] hdr = new byte[Ptp.HEADER_SIZE];
        byte[] chunk = new byte[32 * 1024];
        while (true) {
            link.readFully(hdr, 0, hdr.length);
            long len = Ptp.u32(hdr, 0);
            int type = Ptp.u16(hdr, 4);
            int code = Ptp.u16(hdr, 6);
            if (len < Ptp.HEADER_SIZE) throw new IOException("Malformed PTP container, length " + len);
            long payload = len - Ptp.HEADER_SIZE;
            switch (type) {
                case Ptp.TYPE_DATA: {
                    if (len == 0xFFFFFFFFL) throw new IOException("Unbounded data container not supported");
                    OutputStream dst = sink;
                    if (dst == null) {
                        if (payload > MAX_BUFFERED_DATA) throw new IOException("Data phase too large to buffer: " + payload);
                        if (buffered == null) buffered = new ByteArrayOutputStream((int) payload);
                        dst = buffered;
                    }
                    long left = payload;
                    while (left > 0) {
                        int take = (int) Math.min(chunk.length, left);
                        link.readFully(chunk, 0, take);
                        dst.write(chunk, 0, take);
                        left -= take;
                    }
                    break;
                }
                case Ptp.TYPE_RESPONSE: {
                    byte[] p = new byte[(int) payload];
                    if (p.length > 0) link.readFully(p, 0, p.length);
                    long[] rp = new long[p.length / 4];
                    for (int i = 0; i < rp.length; i++) rp[i] = Ptp.u32(p, 4 * i);
                    return new Response(code, rp, buffered == null ? new byte[0] : buffered.toByteArray());
                }
                default: { // events or garbage on the command pipe: skip
                    skip(payload, chunk);
                }
            }
        }
    }

    /** The camera does not queue commands; the session is still usable, one command at a time. */
    public static final class PipelineRefused extends IOException {
        public PipelineRefused(String message) {
            super(message);
        }
    }

    /**
     * Sends several commands of the same operation back to back, then reads the answers in order (one
     * data phase at most each, buffered). Saves the round trips when the responder queues commands; PTP does
     * not promise that, so callers must be ready for a timeout and fall back to {@link #transact}.
     */
    public synchronized List<Response> transactPipelined(int opcode, List<long[]> paramsList) throws IOException {
        java.util.function.Consumer<String> w = wire;
        if (w == null) return pipelinedOnce(opcode, paramsList);
        long t0 = System.nanoTime();
        String what = String.format("%04X", opcode) + " ×" + paramsList.size() + " in coda";
        try {
            List<Response> r = pipelinedOnce(opcode, paramsList);
            w.accept(what + " -> ok in " + ms(t0) + " ms");
            return r;
        } catch (IOException | RuntimeException e) {
            w.accept(what + " -> " + e.getClass().getSimpleName() + " dopo " + ms(t0) + " ms: " + e.getMessage());
            throw e;
        }
    }

    private List<Response> pipelinedOnce(int opcode, List<long[]> paramsList) throws IOException {
        int count = paramsList.size();
        long firstTid = transactionId + 1L;
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (long[] params : paramsList) {
            int tid = ++transactionId;
            int n = params == null ? 0 : params.length;
            byte[] cmd = new byte[Ptp.HEADER_SIZE + 4 * n];
            Ptp.putU32(cmd, 0, cmd.length);
            Ptp.putU16(cmd, 4, Ptp.TYPE_COMMAND);
            Ptp.putU16(cmd, 6, opcode);
            Ptp.putU32(cmd, 8, tid);
            for (int i = 0; i < n; i++) Ptp.putU32(cmd, Ptp.HEADER_SIZE + 4 * i, params[i]);
            all.write(cmd, 0, cmd.length);
        }
        byte[] cmds = all.toByteArray();
        link.write(cmds, 0, cmds.length); // one write: the commands leave in as few packets as possible
        List<Response> out = new ArrayList<>(count);
        byte[] hdr = new byte[Ptp.HEADER_SIZE];
        byte[] chunk = new byte[32 * 1024];
        ByteArrayOutputStream buffered = null;
        while (out.size() < count) {
            link.readFully(hdr, 0, hdr.length);
            long len = Ptp.u32(hdr, 0);
            int type = Ptp.u16(hdr, 4);
            int code = Ptp.u16(hdr, 6);
            long tid = Ptp.u32(hdr, 8);
            if (len < Ptp.HEADER_SIZE) throw new IOException("Malformed PTP container, length " + len);
            long payload = len - Ptp.HEADER_SIZE;
            long expected = firstTid + out.size();
            if ((type == Ptp.TYPE_DATA || type == Ptp.TYPE_RESPONSE) && tid != expected) {
                if (tid > expected && tid <= transactionId) {
                    // The camera dropped the queued commands and answered a later one only. Read that answer
                    // to the end so the session stays in step, then let the caller go one at a time.
                    skip(payload, chunk);
                    if (type == Ptp.TYPE_DATA) {
                        while (true) {
                            link.readFully(hdr, 0, hdr.length);
                            long l2 = Ptp.u32(hdr, 0);
                            int t2 = Ptp.u16(hdr, 4);
                            if (l2 < Ptp.HEADER_SIZE) throw new IOException("Malformed PTP container, length " + l2);
                            skip(l2 - Ptp.HEADER_SIZE, chunk);
                            if (t2 == Ptp.TYPE_RESPONSE) break;
                        }
                    }
                    throw new PipelineRefused("Pipelined answer out of order: transaction " + tid + ", expected " + expected);
                }
                throw new IOException("Pipelined answer out of order: transaction " + tid + ", expected " + expected);
            }
            if (type == Ptp.TYPE_DATA) {
                if (payload > MAX_BUFFERED_DATA) throw new IOException("Data phase too large to buffer: " + payload);
                if (buffered == null) buffered = new ByteArrayOutputStream((int) payload);
                long left = payload;
                while (left > 0) {
                    int take = (int) Math.min(chunk.length, left);
                    link.readFully(chunk, 0, take);
                    buffered.write(chunk, 0, take);
                    left -= take;
                }
            } else if (type == Ptp.TYPE_RESPONSE) {
                byte[] p = new byte[(int) payload];
                if (p.length > 0) link.readFully(p, 0, p.length);
                long[] rp = new long[p.length / 4];
                for (int i = 0; i < rp.length; i++) rp[i] = Ptp.u32(p, 4 * i);
                out.add(new Response(code, rp, buffered == null ? new byte[0] : buffered.toByteArray()));
                buffered = null;
            } else {
                skip(payload, chunk);
            }
        }
        return out;
    }

    private void skip(long count, byte[] scratch) throws IOException {
        while (count > 0) {
            int take = (int) Math.min(scratch.length, count);
            link.readFully(scratch, 0, take);
            count -= take;
        }
    }

    private Response ok(int opcode, long[] params, OutputStream sink) throws IOException {
        Response r = transact(opcode, params, sink);
        if (r.code != Ptp.RC_OK) throw new PtpResponseException(opcode, r.code);
        return r;
    }

    // ------------------------------------------------------------------ session

    public void openSession() throws IOException {
        openSession(true);
    }

    /**
     * OpenSession with session id 1 and transaction id 1 (what Fuji bodies expect; fudge does the same).
     * Fuji Wi-Fi skips GetDeviceInfo: neither XApp nor fudge sends it there before the camera is set up.
     */
    public void openSession(boolean readDeviceInfo) throws IOException {
        Response r = transact(Ptp.OC_OPEN_SESSION, new long[]{1}, null);
        if (r.code != Ptp.RC_OK && r.code != Ptp.RC_SESSION_ALREADY_OPEN) {
            throw new PtpResponseException(Ptp.OC_OPEN_SESSION, r.code);
        }
        open = true;
        if (!readDeviceInfo) return;
        try {
            loadDeviceInfo();
        } catch (PtpResponseException e) {
            supportedOps = null; // keep going: capabilities are only an optimisation
        }
    }

    /** Transaction id the next command will carry (TerminateOpenCapture needs the one of InitiateOpenCapture). */
    public synchronized long nextTransactionId() {
        return transactionId + 1L;
    }

    public FujiWifi fujiWifi() {
        return fujiWifi;
    }

    void attach(FujiWifi f) {
        this.fujiWifi = f;
    }

    // ------------------------------------------------------------------ device properties

    /** GetDevicePropValue; throws PtpResponseException when the camera refuses. */
    public byte[] getPropValue(int code) throws IOException {
        return ok(Ptp.OC_GET_DEVICE_PROP_VALUE, new long[]{code}, null).data;
    }

    /** Value as an unsigned int (1, 2 or 4 bytes), or -1 when unreadable, like fudge's ptp_parse_prop_value. */
    public long getPropInt(int code) throws IOException {
        byte[] d;
        try {
            d = getPropValue(code);
        } catch (PtpResponseException e) {
            return -1;
        }
        return parsePropInt(d);
    }

    static long parsePropInt(byte[] d) {
        switch (d.length) {
            case 1: return d[0] & 0xFF;
            case 2: return Ptp.u16(d, 0);
            case 4: return Ptp.u32(d, 0);
            default: return -1;
        }
    }

    public void setProp16(int code, int value) throws IOException {
        byte[] v = new byte[2];
        Ptp.putU16(v, 0, value);
        Response r = transact(Ptp.OC_SET_DEVICE_PROP_VALUE, new long[]{code}, v, null);
        if (r.code != Ptp.RC_OK) throw new PtpResponseException(Ptp.OC_SET_DEVICE_PROP_VALUE, r.code);
    }

    public void setProp32(int code, long value) throws IOException {
        byte[] v = new byte[4];
        Ptp.putU32(v, 0, value);
        Response r = transact(Ptp.OC_SET_DEVICE_PROP_VALUE, new long[]{code}, v, null);
        if (r.code != Ptp.RC_OK) throw new PtpResponseException(Ptp.OC_SET_DEVICE_PROP_VALUE, r.code);
    }

    /** Any operation, response code returned instead of thrown; data phase discarded. */
    public int call(int opcode, long... params) throws IOException {
        return transact(opcode, params, null, DISCARD).code;
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    private void loadDeviceInfo() throws IOException {
        Response r = ok(Ptp.OC_GET_DEVICE_INFO, null, null);
        supportedOps = parseSupportedOps(r.data);
    }

    /** Returns null when the camera sent an empty/unparseable DeviceInfo (X-T4 does that over Wi-Fi). */
    static Set<Integer> parseSupportedOps(byte[] d) {
        try {
            if (d == null || d.length < 20) return null;
            int[] pos = {0};
            pos[0] += 2 + 4 + 2; // StandardVersion, VendorExtensionID, VendorExtensionVersion
            readString(d, pos);  // VendorExtensionDesc
            pos[0] += 2;         // FunctionalMode
            long count = Ptp.u32(d, pos[0]);
            pos[0] += 4;
            if (count > 1000 || pos[0] + count * 2 > d.length) return null;
            Set<Integer> ops = new HashSet<>();
            for (int i = 0; i < count; i++) {
                ops.add(Ptp.u16(d, pos[0]));
                pos[0] += 2;
            }
            return ops;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** true / false when the camera advertises it, or defaultIfUnknown when we could not read DeviceInfo. */
    public boolean supports(int opcode, boolean defaultIfUnknown) {
        return supportedOps == null ? defaultIfUnknown : supportedOps.contains(opcode);
    }

    public boolean isOpen() {
        return open;
    }

    // ------------------------------------------------------------------ browsing

    public List<Long> getStorageIds() throws IOException {
        return parseU32Array(ok(Ptp.OC_GET_STORAGE_IDS, null, null).data);
    }

    /** All objects of a storage (flat, parent=0). */
    public List<Long> getObjectHandles(long storageId) throws IOException {
        return parseU32Array(ok(Ptp.OC_GET_OBJECT_HANDLES, new long[]{storageId, 0, 0}, null).data);
    }

    public ObjectInfo getObjectInfo(long handle) throws IOException {
        return parseObjectInfo(handle, ok(Ptp.OC_GET_OBJECT_INFO, new long[]{handle}, null).data);
    }

    static ObjectInfo parseObjectInfo(long handle, byte[] d) throws IOException {
        if (d.length < 53) throw new IOException("ObjectInfo too short: " + d.length);
        long storage = Ptp.u32(d, 0);
        int format = Ptp.u16(d, 4);
        long size = Ptp.u32(d, 8);
        long w = Ptp.u32(d, 26); // ImagePixWidth
        long h = Ptp.u32(d, 30); // ImagePixHeight
        int[] pos = {52};
        String name = readString(d, pos);
        String date = readString(d, pos);
        return new ObjectInfo(handle, storage, format, size, name, date, w, h);
    }

    /** 64-bit size via GetObjectPropValue(ObjectSize), for objects >= 4 GiB. */
    public long getObjectSize64(long handle) throws IOException {
        byte[] d = ok(Ptp.OC_GET_OBJECT_PROP_VALUE, new long[]{handle, Ptp.OPC_OBJECT_SIZE}, null).data;
        if (d.length < 8) throw new IOException("ObjectSize property too short");
        return Ptp.u32(d, 0) | (Ptp.u32(d, 4) << 32);
    }

    public byte[] getThumb(long handle) throws IOException {
        return ok(Ptp.OC_GET_THUMB, new long[]{handle}, null).data;
    }

    /** Fuji 0x9055, what XApp calls over Wi-Fi instead of GetThumb. */
    public byte[] getExtensionThumb(long handle) throws IOException {
        return ok(Ptp.OC_FUJI_GET_EXT_THUMB, new long[]{handle}, null).data;
    }

    // ------------------------------------------------------------------ downloads

    public void getObject(long handle, OutputStream sink) throws IOException {
        ok(Ptp.OC_GET_OBJECT, new long[]{handle}, sink);
    }

    /** Offset and length must fit in 32 bits (objects below 4 GiB). */
    public void getPartialObject(long handle, long offset, long length, OutputStream sink) throws IOException {
        ok(Ptp.OC_GET_PARTIAL_OBJECT, new long[]{handle, offset, length}, sink);
    }

    /**
     * Fuji extension (0x9056) for offsets beyond 4 GiB, as used by the original app:
     * params are {handle, offsetLow, length, offsetHigh}.
     */
    public void getExtensionPartialObject(long handle, long offset, long length, OutputStream sink) throws IOException {
        ok(Ptp.OC_FUJI_GET_EXT_PARTIAL_OBJECT,
                new long[]{handle, offset & 0xFFFFFFFFL, length, offset >>> 32}, sink);
    }

    public void closeSession() {
        if (!open) return;
        try {
            transact(Ptp.OC_CLOSE_SESSION, null, null);
        } catch (IOException ignored) {
            // link may already be dead
        }
        open = false;
    }

    @Override
    public void close() {
        closeSession();
        link.close();
    }

    // ------------------------------------------------------------------ dataset helpers

    static List<Long> parseU32Array(byte[] d) throws IOException {
        List<Long> out = new ArrayList<>();
        if (d.length < 4) return out;
        long n = Ptp.u32(d, 0);
        if (n * 4 + 4 > d.length) throw new IOException("Array truncated");
        for (int i = 0; i < n; i++) out.add(Ptp.u32(d, 4 + 4 * i));
        return out;
    }

    /** PTP string: uint8 char count (including the NUL), then UTF-16LE. */
    static String readString(byte[] d, int[] pos) {
        int n = d[pos[0]++] & 0xFF;
        if (n == 0) return "";
        int bytes = n * 2;
        String s = new String(d, pos[0], bytes, StandardCharsets.UTF_16LE);
        pos[0] += bytes;
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }
}
