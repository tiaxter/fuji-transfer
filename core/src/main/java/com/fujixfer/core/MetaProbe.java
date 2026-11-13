package com.fujixfer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One-off probes of docs/METADATI_E_DIMENSIONI.md, run once after the listing on Fuji Wi-Fi: can the gallery get
 * sizes (and orientation, dates) without reading each file? Log only, nothing else uses the answers. Lightest
 * first: 0x9054 on a few files with the raw bytes, 0x9054 on 20 files in a row for the time, the real sizes of
 * the same few files to compare, MTP GetObjectPropList 0x9805 on one file; last 0x9805 for all objects, which
 * the X-M5 did not answer within the 10 s read timeout on 13 Nov (now given 60 s). Stops at the first lost
 * connection.
 */
public final class MetaProbe {
    static final int OC_GET_OBJECT_PROP_LIST = 0x9805;
    static final long ALL_OBJECTS = 0xFFFFFFFFL;
    static final long ALL_PROPS = 0xFFFFFFFFL;
    /** Files whose 0x9054 bytes are logged and whose real size is read to compare. */
    static final int SAMPLE = 3;
    static final int TIMED = 20;
    static final long ALL_OBJECTS_WAIT_MS = 60_000;

    private MetaProbe() {
    }

    public static void run(TransferEngine eng, List<ObjectInfo> objects, Consumer<String> log) {
        if (objects.isEmpty()) return;
        try {
            PtpSession s = eng.session();
            FujiWifi fuji = s.fujiWifi();
            if (fuji == null) return;
            log.accept("prova metadati: " + objects.size() + " file, modo galleria");
            List<ObjectInfo> sample = new ArrayList<>(objects.subList(0, Math.min(SAMPLE, objects.size())));
            for (ObjectInfo o : sample) {
                Timed t = call(s, Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, o.handle);
                log.accept("prova metadati: 0x9054 " + o.filename + " (handle " + o.handle + ", elenco " + o.size + " B) -> "
                        + Ptp.rcName(t.code) + " in " + t.ms + " ms, " + t.data.length + " B: " + hex(t.data, 256));
                List<String> strings = strings(t.data);
                if (!strings.isEmpty()) log.accept("prova metadati: 0x9054 " + o.filename + " stringhe " + strings);
            }
            int n = Math.min(TIMED, objects.size());
            long t0 = System.nanoTime();
            int ok = 0;
            long slowest = 0;
            for (int i = 0; i < n; i++) {
                Timed t = call(s, Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, objects.get(i).handle);
                if (t.code == Ptp.RC_OK) ok++;
                slowest = Math.max(slowest, t.ms);
            }
            log.accept("prova metadati: 0x9054 su " + n + " file in fila: " + ok + " OK, "
                    + (System.nanoTime() - t0) / 1_000_000 / n + " ms l'uno, il più lento " + slowest + " ms");
            for (ObjectInfo o : sample) {
                long t1 = System.nanoTime();
                ObjectInfo real = eng.withRealSize(o);
                log.accept("prova metadati: dimensione vera " + o.filename + " = " + real.size + " B ("
                        + (System.nanoTime() - t1) / 1_000_000 + " ms)");
            }
            s = eng.session(); // withRealSize may have reconnected
            fuji = s.fujiWifi();
            ObjectInfo first = sample.get(0);
            boolean one = propList(s, first.handle, Ptp.OPC_OBJECT_SIZE, "dimensione di " + first.filename, log);
            propList(s, first.handle, ALL_PROPS, "tutte le proprietà di " + first.filename, log);
            if (one && fuji != null) {
                fuji.expectSlowReply(ALL_OBJECTS_WAIT_MS);
                try {
                    propList(s, ALL_OBJECTS, Ptp.OPC_OBJECT_SIZE, "dimensioni di tutti (attesa fino a 60 s)", log);
                } finally {
                    fuji.endSlowReply();
                }
            } else {
                log.accept("prova metadati: 0x9805 per tutti saltato (rifiutato già su un file)");
            }
            log.accept("prova metadati: finita");
        } catch (IOException e) {
            log.accept("prova metadati interrotta: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 0x9805 {handle, format 0, property, group 0, depth 0}; true when the camera answered OK. */
    private static boolean propList(PtpSession s, long handle, long prop, String what, Consumer<String> log)
            throws IOException {
        Timed t = call(s, OC_GET_OBJECT_PROP_LIST, handle, 0, prop, 0, 0);
        StringBuilder b = new StringBuilder("prova metadati: 0x9805 " + what + " -> " + Ptp.rcName(t.code) + " in " + t.ms
                + " ms, " + t.data.length + " B");
        if (t.code == Ptp.RC_OK) {
            List<String> items = new ArrayList<>();
            long total = parsePropList(t.data, items, 8);
            b.append(", ").append(total < 0 ? "non decodificata" : total + " elementi").append(" ").append(items)
                    .append(" · inizio ").append(hex(t.data, 48));
        }
        log.accept(b.toString());
        return t.code == Ptp.RC_OK;
    }

    private static final class Timed {
        final int code;
        final long ms;
        final byte[] data;

        Timed(int code, long ms, byte[] data) {
            this.code = code;
            this.ms = ms;
            this.data = data;
        }
    }

    /** One transaction; "busy" is repeated once after 500 ms, as the report says, never more. */
    private static Timed call(PtpSession s, int op, long... params) throws IOException {
        for (int attempt = 0; ; attempt++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long t0 = System.nanoTime();
            PtpSession.Response r = s.transact(op, params, out);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r.code == Ptp.RC_DEVICE_BUSY && attempt == 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrotto");
                }
                continue;
            }
            return new Timed(r.code, ms, out.toByteArray());
        }
    }

    /**
     * MTP ObjectPropList: u32 count, then {u32 handle, u16 property, u16 datatype, value}. Returns the count, or -1
     * when the data does not parse; the first {@code max} elements go to {@code items} as "handle:prop=value".
     */
    static long parsePropList(byte[] d, List<String> items, int max) {
        if (d.length < 4) return -1;
        long n = Ptp.u32(d, 0);
        int pos = 4;
        for (long i = 0; i < n; i++) {
            if (pos + 8 > d.length) return -1;
            long handle = Ptp.u32(d, pos);
            int prop = Ptp.u16(d, pos + 4);
            int type = Ptp.u16(d, pos + 6);
            pos += 8;
            String value;
            int size = fixedSize(type);
            if (size > 0) {
                if (pos + size > d.length) return -1;
                value = size <= 8 ? String.valueOf(unsigned(d, pos, size)) : "0x" + hex(java.util.Arrays.copyOfRange(d, pos, pos + size), size);
                pos += size;
            } else if (type == 0xFFFF) {
                int len = d[pos] & 0xFF;
                if (pos + 1 + 2 * len > d.length) return -1;
                StringBuilder sb = new StringBuilder("\"");
                for (int k = 0; k < len; k++) {
                    char c = (char) Ptp.u16(d, pos + 1 + 2 * k);
                    if (c != 0) sb.append(c);
                }
                value = sb.append('"').toString();
                pos += 1 + 2 * len;
            } else {
                items.add(String.format("%d:%04X tipo %04X non letto", handle, prop, type));
                return -1;
            }
            if (items.size() < max) items.add(String.format("%d:%04X=%s", handle, prop, value));
        }
        return n;
    }

    /** Bytes of a PTP fixed-size datatype (INT8..UINT128), 0 for strings, arrays and unknown types. */
    private static int fixedSize(int type) {
        switch (type) {
            case 0x0001: case 0x0002: return 1;
            case 0x0003: case 0x0004: return 2;
            case 0x0005: case 0x0006: return 4;
            case 0x0007: case 0x0008: return 8;
            case 0x0009: case 0x000A: return 16;
            default: return 0;
        }
    }

    private static long unsigned(byte[] d, int pos, int size) {
        long v = 0;
        for (int i = size - 1; i >= 0; i--) v = (v << 8) | (d[pos + i] & 0xFF);
        return v;
    }

    /** PTP strings (u8 length with the NUL, then UTF-16LE) of 3+ printable characters found anywhere in the data. */
    static List<String> strings(byte[] d) {
        List<String> out = new ArrayList<>();
        for (int pos = 0; pos < d.length; pos++) {
            int len = d[pos] & 0xFF;
            if (len < 4 || pos + 1 + 2 * len > d.length) continue;
            StringBuilder sb = new StringBuilder();
            boolean good = true;
            for (int k = 0; k < len - 1 && good; k++) {
                char c = (char) Ptp.u16(d, pos + 1 + 2 * k);
                good = c >= 0x20 && c < 0x7F;
                sb.append(c);
            }
            if (good && Ptp.u16(d, pos + 1 + 2 * (len - 1)) == 0) {
                out.add("@" + pos + " \"" + sb + "\"");
                pos += 2 * len;
            }
        }
        return out;
    }

    private static String hex(byte[] d, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(d.length, max); i++) b.append(String.format("%02x", d[i] & 0xFF));
        return d.length > max ? b.append("…").toString() : b.toString();
    }
}
