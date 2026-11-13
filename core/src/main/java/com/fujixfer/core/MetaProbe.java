package com.fujixfer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One-off probes of docs/METADATI_E_DIMENSIONI.md, run once after the listing on Fuji Wi-Fi: can the gallery get
 * sizes (and orientation, dates) without reading each file? Log only, nothing else uses the answers.
 * Second round (13 Nov, 21:59, X-M5): 0x9805 on one handle answered in 14 ms with the real size of a RAF, 0x9805
 * for all objects got no answer in 60 s, 0x9054 refused the listing's handles (InvalidObjectHandle) although it
 * accepts XApp's 0x10000001. Now: 0x9805 size of the sample (JPGs included, whose gallery size is fake) against
 * the real one, 0x9805 on 50 files in a row for the time, 0x9054 with 0x10000000 + handle.
 */
public final class MetaProbe {
    static final int OC_GET_OBJECT_PROP_LIST = 0x9805;
    static final long ALL_PROPS = 0xFFFFFFFFL;
    /** XApp's 0x9054 handle for the first file; the listing's handles plus this base are tried. */
    static final long EXT_HANDLE_BASE = 0x10000000L;
    /** Files compared with their real size, at least one JPG among them. */
    static final int SAMPLE = 4;
    static final int TIMED = 50;

    private MetaProbe() {
    }

    public static void run(TransferEngine eng, List<ObjectInfo> objects, Consumer<String> log) {
        if (objects.isEmpty()) return;
        try {
            PtpSession s = eng.session();
            if (s.fujiWifi() == null) return;
            log.accept("prova metadati: " + objects.size() + " file, modo galleria");
            List<ObjectInfo> sample = sample(objects);
            long[] viaList = new long[sample.size()];
            for (int i = 0; i < sample.size(); i++) viaList[i] = sizeViaPropList(s, sample.get(i).handle);
            int n = Math.min(TIMED, objects.size());
            long t0 = System.nanoTime();
            int ok = 0;
            long slowest = 0;
            for (int i = 0; i < n; i++) {
                long t1 = System.nanoTime();
                if (sizeViaPropList(s, objects.get(i).handle) >= 0) ok++;
                slowest = Math.max(slowest, (System.nanoTime() - t1) / 1_000_000);
            }
            log.accept("prova metadati: 0x9805 dimensione su " + n + " file in fila: " + ok + " OK, "
                    + (System.nanoTime() - t0) / 1_000_000 / n + " ms l'uno, il più lento " + slowest + " ms");
            for (int i = 0; i < sample.size(); i++) {
                ObjectInfo o = sample.get(i);
                Timed t = call(s, Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, EXT_HANDLE_BASE + o.handle);
                log.accept("prova metadati: 0x9054 " + o.filename + " (handle 0x" + Long.toHexString(EXT_HANDLE_BASE + o.handle)
                        + ") -> " + Ptp.rcName(t.code) + " in " + t.ms + " ms, " + t.data.length + " B: " + hex(t.data, 256));
                List<String> strings = strings(t.data);
                if (!strings.isEmpty()) log.accept("prova metadati: 0x9054 " + o.filename + " stringhe " + strings);
            }
            for (int i = 0; i < sample.size(); i++) {
                ObjectInfo o = sample.get(i);
                ObjectInfo real = eng.withRealSize(o);
                log.accept("prova metadati: " + o.filename + " elenco " + o.size + " B, 0x9805 " + viaList[i] + " B, vera "
                        + real.size + " B" + (viaList[i] == real.size ? " (0x9805 giusta)" : " (0x9805 DIVERSA)"));
            }
            log.accept("prova metadati: finita");
        } catch (IOException e) {
            log.accept("prova metadati interrotta: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The first files of the listing, plus the first JPG and the first RAF if they are not among them. */
    static List<ObjectInfo> sample(List<ObjectInfo> objects) {
        List<ObjectInfo> out = new ArrayList<>(objects.subList(0, Math.min(SAMPLE - 2, objects.size())));
        for (String ext : new String[]{".JPG", ".RAF"}) {
            for (ObjectInfo o : objects) {
                if (o.filename.toUpperCase(java.util.Locale.ROOT).endsWith(ext)) {
                    if (!out.contains(o)) out.add(o);
                    break;
                }
            }
        }
        return out;
    }

    /** ObjectSize from 0x9805 {handle, 0, 0xDC04, 0, 0}; -1 when refused or not parsed. */
    private static long sizeViaPropList(PtpSession s, long handle) throws IOException {
        Timed t = call(s, OC_GET_OBJECT_PROP_LIST, handle, 0, Ptp.OPC_OBJECT_SIZE, 0, 0);
        if (t.code != Ptp.RC_OK || t.data.length < 4 || Ptp.u32(t.data, 0) < 1 || t.data.length < 16) return -1;
        int type = Ptp.u16(t.data, 10);
        if (type == 0x0008 && t.data.length >= 20) return unsigned(t.data, 12, 8);
        if (type == 0x0006) return unsigned(t.data, 12, 4);
        return -1;
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
