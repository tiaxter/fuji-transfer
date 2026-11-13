package com.fujixfer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One-off probes of docs/METADATI_E_DIMENSIONI.md, run once after the listing on Fuji Wi-Fi. Log only.
 * Sizes are settled (13 Nov: in gallery mode every command gives the JPGs' placeholder size). Fourth round: the
 * handles 0x9054 takes. The listing's handles and 0x10000000 + handle were refused, XApp's 0x10000001 is accepted:
 * try 0x10000001.. in a row and log the raw answer and the strings in it, whose file name shows which photo each
 * one is (position in the gallery order of 0xD621, in the listing, or something else), plus the end of the range.
 */
public final class MetaProbe {
    static final long EXT_HANDLE_BASE = 0x10000000L;
    /** Answers logged in full. */
    static final int DUMPED = 5;
    static final int TIMED = 20;

    private MetaProbe() {
    }

    public static void run(TransferEngine eng, List<ObjectInfo> objects, Consumer<String> log) {
        if (objects.isEmpty()) return;
        try {
            PtpSession s = eng.session();
            if (s.fujiWifi() == null) return;
            int n = objects.size();
            StringBuilder first = new StringBuilder();
            for (int i = 0; i < Math.min(DUMPED, n); i++) {
                first.append(i == 0 ? "" : ", ").append(objects.get(i).handle).append('=').append(objects.get(i).filename);
            }
            log.accept("prova metadati: " + n + " file, modo galleria; elenco dell'app: " + first);
            for (int i = 1; i <= DUMPED; i++) dump(s, EXT_HANDLE_BASE + i, log);
            dump(s, EXT_HANDLE_BASE + n, log);
            dump(s, EXT_HANDLE_BASE + n + 1, log);
            long t0 = System.nanoTime();
            int ok = 0;
            long slowest = 0;
            int timed = Math.min(TIMED, n);
            for (int i = 1; i <= timed; i++) {
                Timed t = call(s, Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, EXT_HANDLE_BASE + i);
                if (t.code == Ptp.RC_OK) ok++;
                slowest = Math.max(slowest, t.ms);
            }
            log.accept("prova metadati: 0x9054 su 0x10000001..0x" + Long.toHexString(EXT_HANDLE_BASE + timed) + ": " + ok
                    + " OK, " + (System.nanoTime() - t0) / 1_000_000 / timed + " ms l'uno, il più lento " + slowest + " ms");
            log.accept("prova metadati: finita");
        } catch (IOException e) {
            log.accept("prova metadati interrotta: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void dump(PtpSession s, long handle, Consumer<String> log) throws IOException {
        Timed t = call(s, Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, handle);
        log.accept("prova metadati: 0x9054 0x" + Long.toHexString(handle) + " -> " + Ptp.rcName(t.code) + " in " + t.ms
                + " ms, " + t.data.length + " B: " + hex(t.data, 400));
        List<String> strings = strings(t.data);
        if (!strings.isEmpty()) log.accept("prova metadati: 0x9054 0x" + Long.toHexString(handle) + " stringhe " + strings);
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
