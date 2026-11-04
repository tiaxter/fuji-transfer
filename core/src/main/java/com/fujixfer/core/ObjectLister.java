package com.fujixfer.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Enumerates files (not folders) on all storages of the camera. */
final class ObjectLister {
    private ObjectLister() {}

    /** Receives the listing while it grows, newest objects first. */
    interface Sink {
        void partial(List<ObjectInfo> soFar, int done, int total);

        /** Between two groups: other work on the same session (thumbnails, a download). True if something ran. */
        default boolean idle() throws IOException {
            return false;
        }

        /**
         * Handles to read before the others, if still to be read (the days the user is looking at); null or
         * empty = keep the order. Asked before every group.
         */
        default List<Long> priority() {
            return null;
        }
    }

    /**
     * How many GetObjectInfo to keep in flight on Fuji Wi-Fi. Starts at 4 and, while the time per file keeps
     * dropping by at least 15%, doubles up to 32; settles on the best size. If the camera stops answering with
     * several requests queued, the next connection uses half of the last size that worked (1 if none did).
     */
    static final class Tuning {
        static final int MAX = 32;
        static final int WINDOW = 24;
        int batch = 4;
        int best = 0;          // size with the best time so far (0 = none measured)
        double bestMs = 0;
        boolean settled;
        int windowFiles;
        long windowNanos;

        Tuning() {
        }

        Tuning(int fixed) {
            batch = fixed;
            settled = true;
        }

        void measured(int files, long nanos, FujiWifi fuji) {
            if (settled || batch == 1) return;
            windowFiles += files;
            windowNanos += nanos;
            if (windowFiles < WINDOW) return;
            double ms = windowNanos / 1e6 / windowFiles;
            windowFiles = 0;
            windowNanos = 0;
            if (best == 0 || ms < bestMs * 0.85) {
                fuji.trace(String.format(java.util.Locale.ROOT, "blocchi da %d: %.0f ms a file", batch, ms));
                best = batch;
                bestMs = ms;
                if (batch < MAX) {
                    batch *= 2;
                    return;
                }
            } else {
                fuji.trace(String.format(java.util.Locale.ROOT, "blocchi da %d: %.0f ms a file, non meglio di %d", batch, ms, best));
                batch = best;
            }
            settled = true;
            fuji.trace("dimensione blocco scelta: " + batch);
        }

        void failed() {
            batch = best > 1 ? Math.max(1, best / 2) : 1;
            best = 0;
            settled = true;
        }
    }

    static List<ObjectInfo> listAll(PtpSession s) throws IOException {
        return listAll(s, null, null, new Boolean[1], new Tuning(1));
    }

    /**
     * @param known handle -> info already known (an earlier listing of this camera, or the part fetched before
     *              a dropped connection). Reused when spot checks show the numbering is unchanged; filled
     *              with every object fetched, so a retry continues where it stopped. May be null.
     */
    /**
     * @param batch how many GetObjectInfo to send together on Fuji Wi-Fi; adjusted while listing (see Tuning).
     */
    static List<ObjectInfo> listAll(PtpSession s, Map<Long, ObjectInfo> known, Sink sink, Boolean[] realSizes,
                                    Tuning batch) throws IOException {
        FujiWifi fuji = s.fujiWifi();
        if (fuji != null) return listFujiWifi(s, fuji, known == null ? new TreeMap<>() : known, sink, realSizes, batch);
        List<ObjectInfo> out = new ArrayList<>();
        for (long storage : s.getStorageIds()) {
            for (long handle : s.getObjectHandles(storage)) {
                ObjectInfo info = s.getObjectInfo(handle);
                if (!info.isFolder()) out.add(info);
            }
        }
        return out;
    }

    /**
     * Fuji Wi-Fi has no GetObjectHandles: handles are 1..ObjectCount and each needs one GetObjectInfo round
     * trip, which is what makes a big card slow. So: newest first (the user wants those), partial results to
     * the sink, and objects already known are not asked again when the lowest and highest known handle still
     * point at the same files (a deletion or a renumbering changes one of them, then everything is re-read).
     */
    /** @param realSizes [0]: list in real-size mode; null = not decided yet (probe once, then remember). */
    private static List<ObjectInfo> listFujiWifi(PtpSession s, FujiWifi fuji, Map<Long, ObjectInfo> known, Sink sink,
                                                 Boolean[] realSizes, Tuning batch) throws IOException {
        List<Long> handles = fuji.handles();
        int n = handles.size();
        List<Long> order = new ArrayList<>(handles);
        if (fuji.newestLast()) Collections.reverse(order);
        if (realSizes[0] == null) {
            if (fuji.isXApp()) {
                // XApp browses its gallery with 0xD226 = 0 and switches to 2 only to import a file. Reading all the
                // ObjectInfo in mode 2 cost the X-M5 ~250 ms each (56 ms in mode 0) and, a minute or so into the
                // listing, it slowed down and turned its Wi-Fi off. List like XApp; downloads ask for the real size.
                realSizes[0] = false;
                fuji.trace("elenco come XApp: dimensioni reali solo al download");
            } else {
                realSizes[0] = !fasterWithoutRealSizes(s, fuji, order);
            }
        }
        boolean real = realSizes[0];
        if (real) fuji.beginRealSizes();
        else fuji.resetSizeMode();
        boolean broken = false;
        try {
            if (!known.isEmpty() && !stillValid(s, known, n)) known.clear();
            int done = 0;
            long t0 = System.currentTimeMillis();
            int fetched = 0;
            long lastT = t0;
            int lastFetched = 0;
            int slowLogged = 0;
            Pace pace = new Pace();
            long lastReport = 0; // first group reported at once: the grid appears immediately
            List<Long> todo = new ArrayList<>();
            for (long h : order) if (!known.containsKey(h)) todo.add(h);
            done = n - todo.size();
            if (batch.batch > 1 && !todo.isEmpty()) fuji.trace("elenco a blocchi da " + batch.batch);
            for (int i = 0; i < todo.size(); ) {
                int k = Math.min(Math.max(1, batch.batch), todo.size() - i);
                if (sink != null) promote(todo, i, k, sink.priority());
                List<Long> group = todo.subList(i, i + k);
                long g0 = System.nanoTime();
                if (k == 1) {
                    try {
                        ObjectInfo got = s.getObjectInfo(group.get(0));
                        known.put(group.get(0), got);
                        long ms = (System.nanoTime() - g0) / 1_000_000;
                        if (ms >= SLOW_MS && slowLogged++ < 8) fuji.trace("elenco: " + got.filename + " ha richiesto " + ms + " ms");
                        pace.read(ms, fuji);
                    } catch (PtpResponseException e) {
                        if (e.getResponseCode() != Ptp.RC_INVALID_OBJECT_HANDLE) throw e; // else: deleted meanwhile
                    }
                } else {
                    fetchGroup(s, fuji, group, known, batch);
                }
                batch.measured(k, System.nanoTime() - g0, fuji);
                i += k;
                fetched += k;
                done += k;
                if (fetched % 100 < k) {
                    long t = System.currentTimeMillis();
                    fuji.trace("elenco: " + fetched + " letti, ultimi " + (fetched - lastFetched) + " a "
                            + (t - lastT) / Math.max(1, fetched - lastFetched) + " ms l'uno");
                    lastT = t;
                    lastFetched = fetched;
                }
                long now = System.currentTimeMillis();
                if (sink != null && (now - lastReport >= 700 || done == n)) {
                    lastReport = now;
                    sink.partial(files(known, n), done, n);
                }
                if (sink != null && i < todo.size() && sink.idle()) {
                    // thumbnails or a download ran in between: put the listing's size mode back
                    if (real) fuji.beginRealSizes();
                    else fuji.resetSizeMode();
                }
            }
            if (sink != null && todo.isEmpty()) sink.partial(files(known, n), n, n);
            return files(known, n);
        } catch (IOException e) {
            // a protocol error or a timeout leaves the session in an unknown state: do not send anything more
            // on it (it would wait for an answer that never comes); the caller reconnects
            if (!(e instanceof PtpResponseException)) broken = true;
            throw e;
        } finally {
            if (real && !broken) fuji.endRealSizes();
        }
    }

    /**
     * On 3 Nov the X-M5 answered each GetObjectInfo slower and slower (130 ms, then 0.5, 1.5, 3, 8 s) and then
     * turned its Wi-Fi off. When the recent reads get much slower than the first ones, stop for a few seconds
     * so the camera can catch up, and say so in the log (whether the times come back down tells what it was).
     */
    static final class Pace {
        private long baseSum;
        private int baseCount;
        private double recent = -1;
        private int pauses;

        void read(long ms, FujiWifi fuji) throws IOException {
            if (baseCount < 30) {
                baseSum += ms;
                baseCount++;
                return;
            }
            long base = Math.max(60, baseSum / baseCount);
            recent = recent < 0 ? ms : recent * 0.8 + ms * 0.2;
            if (recent < Math.max(400, 3 * base)) return;
            long pause = Math.min(10_000, 3_000L << Math.min(pauses, 2));
            pauses++;
            fuji.trace(String.format(java.util.Locale.ROOT,
                    "elenco: la camera rallenta (%.0f ms a file, all'inizio %d): pausa di %d s", recent, base, pause / 1000));
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrotto");
            }
            recent = base;
        }
    }

    /** A single GetObjectInfo slower than this is named in the log (the first few only). */
    private static final long SLOW_MS = 400;

    /** Moves up to {@code k} wanted handles still to read (after position {@code from}) to the front of what is left. */
    static void promote(List<Long> todo, int from, int k, List<Long> wanted) {
        if (wanted == null || wanted.isEmpty()) return;
        java.util.Set<Long> want = new java.util.HashSet<>(wanted);
        int placed = 0;
        for (int j = from; j < todo.size() && placed < k; j++) {
            Long h = todo.get(j);
            if (!want.contains(h)) continue;
            if (j != from + placed) {
                todo.remove(j);
                todo.add(from + placed, h);
            }
            placed++;
        }
    }

    /** Several GetObjectInfo in flight at once; on trouble the size drops for the next attempt. */
    private static void fetchGroup(PtpSession s, FujiWifi fuji, List<Long> group, Map<Long, ObjectInfo> known,
                                   Tuning batch) throws IOException {
        List<long[]> params = new ArrayList<>();
        for (long h : group) params.add(new long[]{h});
        List<PtpSession.Response> rs;
        try {
            rs = s.transactPipelined(Ptp.OC_GET_OBJECT_INFO, params);
        } catch (PtpSession.PipelineRefused e) {
            // The camera answers only the last of several queued commands. Nothing is wrong with the session:
            // list this group one by one and stay at 1 from now on.
            int was = batch.batch;
            batch.failed();
            fuji.trace("la camera non accoda " + was + " richieste (" + e.getMessage() + "): una alla volta");
            for (long h : group) {
                try {
                    known.put(h, s.getObjectInfo(h));
                } catch (PtpResponseException re) {
                    if (re.getResponseCode() != Ptp.RC_INVALID_OBJECT_HANDLE) throw re;
                }
            }
            return;
        } catch (IOException e) {
            int was = batch.batch;
            batch.failed(); // the camera did not answer several queued requests
            fuji.trace("la camera non regge " + was + " richieste insieme (" + e.getMessage() + "): passo a " + batch.batch);
            throw e; // the session is in an unknown state: reconnect, keep what was read
        }
        for (int j = 0; j < rs.size(); j++) {
            PtpSession.Response r = rs.get(j);
            if (r.code == Ptp.RC_OK) {
                known.put(group.get(j), PtpSession.parseObjectInfo(group.get(j), r.data));
            } else if (r.code != Ptp.RC_INVALID_OBJECT_HANDLE) {
                throw new PtpResponseException(Ptp.OC_GET_OBJECT_INFO, r.code);
            }
        }
    }

    /**
     * GetObjectInfo over Wi-Fi took about 0.5 s per file on an X-M5 while the session ops took ~50 ms. The
     * suspect is the real-size mode (0xD226 = 2) that we keep on during the listing. Time one file with and
     * without it: if both modes report the same sizes and the plain one is clearly faster, list without it
     * (downloads still switch it on, and re-read the size, before transferring).
     */
    private static boolean fasterWithoutRealSizes(PtpSession s, FujiWifi fuji, List<Long> order) throws IOException {
        if (order.isEmpty()) return false;
        int k = 1;
        List<Long> probe = order.subList(0, k);
        long[] sizesReal = new long[k];
        long[] sizesPlain = new long[k];
        long tReal;
        long tPlain;
        try {
            fuji.beginRealSizes();
            long a = System.nanoTime();
            for (int i = 0; i < k; i++) sizesReal[i] = s.getObjectInfo(probe.get(i)).size;
            tReal = (System.nanoTime() - a) / (k * 1_000_000L);
            fuji.endRealSizes();
            fuji.resetSizeMode();
            a = System.nanoTime();
            for (int i = 0; i < k; i++) sizesPlain[i] = s.getObjectInfo(probe.get(i)).size;
            tPlain = (System.nanoTime() - a) / (k * 1_000_000L);
        } catch (PtpResponseException e) {
            return false;
        }
        boolean sameSizes = java.util.Arrays.equals(sizesReal, sizesPlain);
        boolean fast = sameSizes && tPlain * 2 < tReal;
        fuji.trace("ObjectInfo: " + tReal + " ms con dimensioni reali, " + tPlain + " ms senza; dimensioni "
                + sizesReal[0] + "/" + sizesPlain[0] + " -> " + (fast ? "elenco senza" : "elenco con") + " dimensioni reali");
        return fast;
    }

    private static boolean stillValid(PtpSession s, Map<Long, ObjectInfo> known, int n) throws IOException {
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        for (long h : known.keySet()) {
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        if (hi > n) return false;
        for (long h : lo == hi ? new long[]{lo} : new long[]{lo, hi}) {
            try {
                if (!s.getObjectInfo(h).sameFileAs(known.get(h))) return false;
            } catch (PtpResponseException e) {
                if (e.getResponseCode() == Ptp.RC_INVALID_OBJECT_HANDLE) return false;
                throw e;
            }
        }
        return true;
    }

    private static List<ObjectInfo> files(Map<Long, ObjectInfo> known, int n) {
        List<ObjectInfo> out = new ArrayList<>();
        for (ObjectInfo i : known.values()) if (i.handle <= n && !i.isFolder()) out.add(i);
        return out;
    }
}
