package com.fujixfer.core;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads objects robustly: small GetPartialObject chunks written straight to a .part file,
 * automatic reconnect with backoff, resume from the bytes already on disk, and a whole-object
 * fallback for bodies that do not implement GetPartialObject.
 */
public final class TransferEngine {

    /** Opens a link and a PTP session (OpenSession already done). Called again after every failure. */
    public interface Connector {
        PtpSession connect() throws IOException;
    }

    public interface Listener {
        void onProgress(ObjectInfo info, long done, long total);

        void onRetry(ObjectInfo info, int attempt, Exception cause);

        boolean isCancelled();
    }

    public static final class Options {
        /**
         * Bytes asked per GetPartialObject. USB uses all of it: 20 MiB, as XApp does over USB
         * (backgroundReceiveImageData); fewer requests, and the data still goes to disk as it arrives, so a drop
         * loses nothing already received. Fuji Wi-Fi is capped at {@link FujiWifi#MAX_PARTIAL} (1 MiB, as XApp).
         */
        public int chunkSize = 20 * 1024 * 1024;
        /** Consecutive attempts without any new byte before giving up. */
        public int maxAttemptsWithoutProgress = 8;
        public long backoffBaseMs = 500;
        public long backoffMaxMs = 8000;
        /**
         * Fuji Wi-Fi listing: one GetObjectInfo at a time from the start, without first trying several queued.
         * For a camera already seen refusing the queue (the X-M5 answers only the last of several commands).
         */
        public boolean listOneAtATime;
    }

    public static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;
        public CancelledException() {
            super("Cancelled");
        }
    }

    private final Connector connector;
    private final Options options;
    private volatile PtpSession session;
    private boolean partialSupported = true;
    private boolean extThumbSupported = true;
    /** Incremented for every new session: handles from an older listing may no longer be valid. */
    private int generation;
    private int listedGeneration = -1;
    private Map<String, Long> remap;
    /** Whether Fuji Wi-Fi listings use the real-size mode; decided once by timing (see ObjectLister). */
    private final Boolean[] listRealSizes = new Boolean[1];
    /** GetObjectInfo requests in flight together on Fuji Wi-Fi (tuned while listing). */
    private final ObjectLister.Tuning listBatch;
    private int remapGeneration = -1;
    /**
     * Session and handle of the last object sized by {@link #sizeForDownload}: that GetObjectInfo already
     * checked the handle and switched to original files, so the download that follows needs no second one.
     */
    private int sizedGeneration = -1;
    private long sizedHandle = -1;
    private long sizedSize = -1;
    private String sizedIdentity = "";

    /**
     * Where the time of the last {@link #download(ObjectInfo, FileChannel, Listener)} went: total, waiting for the
     * camera to start each piece, writing to the phone's storage. Retries and their pauses are in the total.
     */
    public static final class Stats {
        /** Bytes added to the file by this download (a resumed file counts only the new part). */
        public long bytes;
        public long nanos;
        public long waitNanos;
        public long writeNanos;
        public int chunks;
        public int retries;

        Stats copy() {
            Stats c = new Stats();
            c.bytes = bytes;
            c.nanos = nanos;
            c.waitNanos = waitNanos;
            c.writeNanos = writeNanos;
            c.chunks = chunks;
            c.retries = retries;
            return c;
        }
    }

    private Stats stats = new Stats();

    /** Statistics of the last download (a copy). */
    public synchronized Stats lastStats() {
        return stats.copy();
    }

    public TransferEngine(Connector connector, Options options) {
        this.connector = connector;
        this.options = options;
        this.listBatch = options.listOneAtATime ? new ObjectLister.Tuning(1) : new ObjectLister.Tuning();
    }

    /**
     * True when this engine's listing ended up one request at a time because the camera did not answer queued
     * ones (not because one at a time was asked for): worth remembering for this camera.
     */
    public boolean listingRefusedQueue() {
        return !options.listOneAtATime && listBatch.refusedQueue;
    }

    public synchronized PtpSession session() throws IOException {
        if (session == null) {
            session = connector.connect();
            generation++;
        }
        return session;
    }

    public synchronized void close() {
        dropSession();
    }

    /** Closes the socket under a command still running, without the lock close() waits for. */
    public void abort() {
        PtpSession s = session;
        if (s != null) s.abortLink();
    }

    private void dropSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** Lists every non-folder object on every storage. Retries on connection loss. */
    public List<ObjectInfo> listObjects(Listener l) throws IOException {
        return listObjects(l, null, null);
    }

    /** Partial listings while a Fuji Wi-Fi listing runs (newest first). */
    public interface ListingListener {
        void onPartial(List<ObjectInfo> soFar, int done, int total);

        /**
         * Called between two groups of the listing, on the listing's thread: run queued work that needs the
         * camera (thumbnail(), download() of objects already listed). Return true if anything ran.
         */
        default boolean onIdle() throws IOException {
            return false;
        }

        /** Handles to read first (see ObjectLister.Sink#priority). */
        default List<Long> priority() {
            return null;
        }
    }

    /**
     * @param previous objects from an earlier connection to the same camera; reused when the camera's numbering
     *                 has not changed, so only new shots are read. May be null.
     */
    public List<ObjectInfo> listObjects(Listener l, List<ObjectInfo> previous, ListingListener partial)
            throws IOException {
        java.util.Map<Long, ObjectInfo> known = new java.util.TreeMap<>();
        if (previous != null) for (ObjectInfo i : previous) known.put(i.handle, i);
        IOException last = null;
        for (int attempt = 1; attempt <= options.maxAttemptsWithoutProgress; attempt++) {
            if (l != null && l.isCancelled()) throw new CancelledException();
            try {
                PtpSession s = session();
                listedGeneration = generation; // objects already read belong to this session: usable right away
                ObjectLister.Sink sink = partial == null ? null : new ObjectLister.Sink() {
                    @Override
                    public void partial(List<ObjectInfo> soFar, int done, int total) {
                        partial.onPartial(soFar, done, total);
                    }

                    @Override
                    public boolean idle() throws IOException {
                        return partial.onIdle();
                    }

                    @Override
                    public List<Long> priority() {
                        return partial.priority();
                    }
                };
                List<ObjectInfo> all = ObjectLister.listAll(s, known, sink, listRealSizes, listBatch);
                listedGeneration = generation;
                return all;
            } catch (IOException e) {
                last = e; // `known` keeps what was read: the retry continues from there
                dropSession();
                if (attempt == options.maxAttemptsWithoutProgress) break;
                if (l != null) l.onRetry(null, attempt, e);
                backoff(attempt);
            }
        }
        throw last;
    }

    /**
     * Downloads info into dir/info.filename and returns the final file. Safe to call again after a
     * crash: the .part file is reused when name, size and date still match.
     */
    public File download(ObjectInfo info, File dir, Listener l) throws IOException {
        info = sizeForDownload(info); // the .part name carries the real size
        File finalFile = new File(dir, info.filename);
        if (finalFile.length() == info.size && info.size > 0) return finalFile;
        File part = new File(dir, info.filename + "." + info.size + "." + sanitize(info.captureDate) + ".part");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            download(info, raf.getChannel(), l);
        } catch (IOException e) {
            if (part.length() > info.size) part.delete();
            throw e;
        }
        if (finalFile.exists() && !finalFile.delete()) throw new IOException("Cannot replace " + finalFile);
        if (!part.renameTo(finalFile)) throw new IOException("Cannot rename " + part + " to " + finalFile);
        return finalFile;
    }

    /**
     * The object as a download needs it: real size (the listing may carry placeholders on Fuji Wi-Fi, and
     * 0xFFFFFFFF for 4 GiB or more) and a handle valid now. One GetObjectInfo on Fuji Wi-Fi, which also switches
     * the camera to original files; {@link #download(ObjectInfo, FileChannel, Listener)} called right after
     * reuses it instead of asking again. Nothing may talk to the camera in between.
     */
    public ObjectInfo sizeForDownload(ObjectInfo info) throws IOException {
        session(); // may reconnect, which changes generation
        if (sizedGeneration == generation && sizedIdentity.equals(identity(info))) {
            info = info.withHandle(sizedHandle).withSize(sizedSize); // already asked, nothing in between
        } else {
            info = realSizeOnFujiWifi(info);
        }
        if (info.sizeOverflow()) info = resolveLargeSize(info);
        return info;
    }

    /**
     * Downloads {@code info} (as returned by {@link #sizeForDownload}) into {@code out}, a channel the caller
     * opened for writing and closes: a .part file, or an item of the phone's media store. Whatever it already
     * holds is kept and the transfer resumes after it, so the caller must hand back only the channel of this
     * same file (name, date and size). Reconnects with backoff like every download.
     */
    public void download(ObjectInfo info, FileChannel out, Listener l) throws IOException {
        if (out.size() > info.size) out.truncate(0); // not this file after all: start over
        ObjectInfo current = info;
        int failures = 0;
        long lastProgress = out.size();
        stats = new Stats();
        long t0 = System.nanoTime();
        long had = out.size(); // a resumed file: only what arrives now counts for the speed
        try {
            loop(info, current, out, failures, lastProgress, l);
        } finally {
            sizedGeneration = -1; // the next download checks its own handle
            stats.nanos = System.nanoTime() - t0;
            stats.bytes = out.size() - had;
        }
        if (out.size() != info.size) {
            out.truncate(0);
            throw new IOException("Size mismatch for " + info.filename + ": " + out.size() + " != " + info.size);
        }
    }

    private void loop(ObjectInfo info, ObjectInfo current, FileChannel out, int failures, long lastProgress, Listener l)
            throws IOException {
        while (out.size() < info.size) {
            if (l != null && l.isCancelled()) throw new CancelledException();
            try {
                session(); // may reconnect, which changes generation
                if (sizedGeneration != generation || sizedHandle != current.handle) {
                    current = ensureHandle(current); // on Fuji Wi-Fi this also switches to original files/real sizes
                }
                transferMore(current, out, info.size, l);
                FujiWifi fuji = session().fujiWifi();
                if (fuji != null) endRealSizesQuietly(fuji);
            } catch (CancelledException e) {
                throw e;
            } catch (PtpResponseException e) {
                if (e.getResponseCode() == Ptp.RC_OPERATION_NOT_SUPPORTED && partialSupported) {
                    partialSupported = false; // switch to whole-object mode, no resume possible
                    out.truncate(0);
                    continue;
                }
                failures = noteFailure(info, out.size(), lastProgress, failures, e, l);
                lastProgress = out.size();
                stats.retries++;
                dropSession();
                backoff(failures);
            } catch (IOException e) {
                failures = noteFailure(info, out.size(), lastProgress, failures, e, l);
                lastProgress = out.size();
                stats.retries++;
                dropSession();
                backoff(failures);
            }
        }
    }

    /** Largest preview kept in memory (XApp reads at most 10 MiB for its zoom view). */
    public static final int PREVIEW_MAX = 16 * 1024 * 1024;

    /**
     * The JPEG a RAF embeds (offset and length at bytes 84 and 88 of its header): one small read of the header,
     * then only that part of the file instead of all of it. In the original-file mode, the gallery mode put
     * back after (like readExif). Null when the file is no RAF, its header gives no JPEG, or the camera refuses
     * the reads; one reconnect at most, like thumbnails.
     */
    public byte[] rafPreview(ObjectInfo info, Listener l) throws IOException {
        sizedGeneration = -1; // the gallery mode comes back after: a later download sizes again
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            FujiWifi fuji = null;
            try {
                PtpSession s = session();
                ObjectInfo cur = locate(info);
                s = session();
                fuji = s.fujiWifi();
                if (!partialSupported || !s.supports(Ptp.OC_GET_PARTIAL_OBJECT, true)) return null;
                if (fuji != null) {
                    fuji.beginRealSizes();
                    s.getObjectInfo(cur.handle); // as before a download: the body expects it
                }
                java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
                s.getPartialObject(cur.handle, 0, 512, head);
                long[] jpg = Exif.rafJpeg(head.toByteArray());
                if (jpg == null || jpg[1] < 4 || jpg[1] > PREVIEW_MAX) return null;
                byte[] d = readRange(s, cur, jpg[0], (int) jpg[1], false, l);
                return isJpeg(d) ? d : null;
            } catch (CancelledException e) {
                throw e;
            } catch (PtpResponseException e) {
                return null; // the camera answered: the caller falls back to the whole file
            } catch (IOException e) {
                last = e;
                fuji = null;
                dropSession();
                if (attempt < 2) backoff(attempt);
            } finally {
                if (fuji != null && session != null) {
                    try {
                        fuji.resetSizeMode();
                    } catch (IOException ignored) {
                        // the next operation reconnects
                    }
                }
            }
        }
        throw last;
    }

    /**
     * A reduced copy made by the camera, as XApp's zoom view asks for it: 0xD22E = 1 (size S) and 0xD226 = 1,
     * then GetObjectInfo gives the copy's size and GetPartialObject its bytes. Fuji Wi-Fi in XApp mode only.
     * Null when not available or when what came is not a whole JPEG: the caller then shows the original. The
     * gallery mode is put back after (0xD226 = 0, as XApp does). One reconnect at most.
     */
    public byte[] smallCopy(ObjectInfo info, Listener l) throws IOException {
        sizedGeneration = -1; // the gallery mode comes back after: a later download sizes again
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            FujiWifi fuji = null;
            try {
                PtpSession s = session();
                if (s.fujiWifi() == null || !s.fujiWifi().isXapp()) return null;
                ObjectInfo cur = locate(info);
                s = session();
                fuji = s.fujiWifi();
                if (!partialSupported || fuji == null) return null;
                fuji.beginSmallCopies();
                ObjectInfo now = s.getObjectInfo(cur.handle);
                if (!now.filename.equals(info.filename)) return null;
                if (now.size <= 0 || now.size > PREVIEW_MAX) {
                    fuji.trace("copia S di " + info.filename + ": dimensione " + now.size + ", non usata");
                    return null;
                }
                byte[] d = readRange(s, cur, 0, (int) now.size, true, l);
                if (!isJpeg(d) || !endsJpeg(d)) {
                    fuji.trace("copia S di " + info.filename + ": " + d.length + " B non sono un JPEG intero");
                    return null;
                }
                return d;
            } catch (CancelledException e) {
                throw e;
            } catch (PtpResponseException e) {
                if (fuji != null) fuji.trace("copia S di " + info.filename + " rifiutata: " + e.getMessage());
                return null;
            } catch (IOException e) {
                last = e;
                fuji = null;
                dropSession();
                if (attempt < 2) backoff(attempt);
            } finally {
                if (fuji != null && session != null) {
                    try {
                        fuji.resetSizeMode();
                    } catch (IOException ignored) {
                        // the next operation reconnects
                    }
                }
            }
        }
        throw last;
    }

    /**
     * {@code length} bytes from {@code offset}, in the chunks downloads use. A short chunk is fine (the X-M5
     * sends 32 bytes less on videos); an empty one ends the read: an error, or with {@code allowShort} the end
     * of an object smaller than announced.
     */
    private byte[] readRange(PtpSession s, ObjectInfo cur, long offset, int length, boolean allowShort, Listener l)
            throws IOException {
        int chunk = s.fujiWifi() != null ? Math.min(options.chunkSize, FujiWifi.MAX_PARTIAL) : options.chunkSize;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(length);
        while (buf.size() < length) {
            if (l != null && l.isCancelled()) throw new CancelledException();
            int before = buf.size();
            s.getPartialObject(cur.handle, offset + before, Math.min(chunk, length - before), buf);
            if (buf.size() == before) {
                if (allowShort && before > 0) break;
                throw new IOException("Empty chunk at " + (offset + before) + " of " + cur.filename);
            }
            if (l != null) l.onProgress(cur, buf.size(), length);
        }
        return buf.toByteArray();
    }

    private static boolean isJpeg(byte[] d) {
        return d.length > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    /** The end-of-image marker in the last bytes: a truncated copy would decode with a grey bottom. */
    private static boolean endsJpeg(byte[] d) {
        for (int i = d.length - 2; i >= Math.max(0, d.length - 512); i--) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) return true;
        }
        return false;
    }

    /**
     * Small preview JPEG for an object, or null when the camera has none for it. One reconnect at most:
     * a missing thumbnail must never stall the UI or a download that follows.
     *
     * @param preferExtension try Fuji 0x9055 first, falling back to GetThumb. Ignored on a Fuji Wi-Fi
     *                        session, which uses GetObjectInfo + GetThumb like fudge.
     */
    public byte[] thumbnail(ObjectInfo info, boolean preferExtension) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                session(); // may reconnect, which changes generation
                boolean cheap = generation == listedGeneration || remapGeneration == generation;
                ObjectInfo cur = locate(info);
                PtpSession s = session();
                FujiWifi fuji = s.fujiWifi();
                if (fuji != null) {
                    if (!cheap) endRealSizesQuietly(fuji); // locate() checked the handle in real-size mode
                    // fudge: GetThumb blocks forever on recent bodies unless GetObjectInfo came right before
                    if (fuji.needsInfoBeforeThumb()) s.getObjectInfo(cur.handle);
                    byte[] d = s.getThumb(cur.handle);
                    return d.length > 0 ? d : null;
                }
                if (preferExtension && extThumbSupported) {
                    try {
                        byte[] d = s.getExtensionThumb(cur.handle);
                        if (d.length > 0) return d;
                    } catch (PtpResponseException e) {
                        if (e.getResponseCode() == Ptp.RC_OPERATION_NOT_SUPPORTED) extThumbSupported = false;
                    }
                }
                byte[] d = s.getThumb(cur.handle);
                return d.length > 0 ? d : null;
            } catch (PtpResponseException e) {
                if (e.getResponseCode() == Ptp.RC_NO_THUMBNAIL_PRESENT
                        || e.getResponseCode() == Ptp.RC_OPERATION_NOT_SUPPORTED) return null;
                throw e; // the camera answered: reconnecting would not help
            } catch (IOException e) {
                last = e;
                dropSession();
                if (attempt < 2) backoff(attempt);
            }
        }
        throw last;
    }

    /** Bytes read from the start of each file for its EXIF (a JPEG's metadata sits in its first ~64 KiB). */
    public static final int EXIF_HEAD = 64 * 1024;

    /**
     * EXIF (position, film simulation) of a few files, read from their first bytes. One read per file from
     * offset 0: a JPEG is parsed as is, a RAF at the JPEG it embeds (usually inside the bytes already read,
     * otherwise one more read there). {@code original}: in the original-file mode (0xD226 = 2, switched once
     * for the group, the gallery mode put back after); otherwise in the gallery mode XApp browses with, which
     * is about 5 times lighter for the camera; whether its copy keeps the EXIF is checked by the caller.
     * Entries are null for files that could not be read. No retry, no reconnect: the caller paces the work.
     */
    public List<Exif.Info> readExif(List<ObjectInfo> group, boolean original) throws IOException {
        sizedGeneration = -1; // the gallery mode comes back after: a later download sizes again
        List<Exif.Info> out = new ArrayList<>();
        PtpSession s = session();
        FujiWifi fuji = s.fujiWifi();
        if (fuji != null && original) fuji.beginRealSizes();
        try {
            for (ObjectInfo info : group) {
                ObjectInfo cur = locate(info);
                if (fuji != null) s.getObjectInfo(cur.handle); // as before a download: the body expects it
                try {
                    out.add(exifAt(s, cur.handle));
                } catch (PtpResponseException e) {
                    out.add(null); // the camera answered: this file only
                }
            }
        } catch (IOException e) {
            // not an answer from the camera: the connection is gone, do not leave it for the next operation
            if (!(e instanceof PtpResponseException)) dropSession();
            throw e;
        } finally {
            if (fuji != null && original && session != null) {
                try {
                    fuji.resetSizeMode();
                } catch (IOException e) {
                    // the next operation reconnects
                }
            }
        }
        return out;
    }

    /** Same as {@link #readExif(List, boolean)} in the original-file mode. */
    public List<Exif.Info> readExif(List<ObjectInfo> group) throws IOException {
        return readExif(group, true);
    }

    private static Exif.Info exifAt(PtpSession s, long handle) throws IOException {
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        s.getPartialObject(handle, 0, EXIF_HEAD, head);
        byte[] d = head.toByteArray();
        long[] jpg = Exif.rafJpeg(d);
        if (jpg == null) return Exif.parse(d); // a JPEG (or the gallery copy of anything)
        long want = Math.min(jpg[1], EXIF_HEAD);
        if (jpg[0] + want <= d.length) {
            return Exif.parse(java.util.Arrays.copyOfRange(d, (int) jpg[0], (int) (jpg[0] + want)));
        }
        head.reset();
        s.getPartialObject(handle, jpg[0], want, head);
        return Exif.parse(head.toByteArray());
    }

    /**
     * Back to the gallery mode (Fuji Wi-Fi in XApp mode: 0xD226 = 0), as XApp leaves the camera after an import:
     * a listing or thumbnails that follow a download (finished or interrupted) then do not run in the heavier
     * original-file mode. Never opens a session; errors are left to the next operation.
     */
    public synchronized void galleryMode() {
        sizedGeneration = -1;
        if (session == null) return;
        FujiWifi fuji = session.fujiWifi();
        if (fuji == null) return;
        try {
            fuji.resetSizeMode();
        } catch (IOException ignored) {
            // the next operation reconnects
        }
    }

    /** False when the last listing was read without the real-size mode: its sizes are placeholders. */
    public boolean listedWithRealSizes() {
        return !Boolean.FALSE.equals(listRealSizes[0]);
    }

    /** The object with its real size (one GetObjectInfo in real-size mode on Fuji Wi-Fi; as is elsewhere). */
    public ObjectInfo withRealSize(ObjectInfo info) throws IOException {
        if (listedWithRealSizes()) return info;
        ObjectInfo r = realSizeOnFujiWifi(info);
        sizedGeneration = -1; // back to the gallery mode below: a download must size again
        try {
            FujiWifi fuji = session().fujiWifi();
            if (fuji != null) fuji.resetSizeMode(); // back to the gallery mode, as XApp leaves it
        } catch (IOException e) {
            // the next operation reconnects
        }
        return r;
    }

    /**
     * A Fuji Wi-Fi listing may have been read without the real-size mode (faster); before downloading, ask
     * again for this file with the mode on and use that size. Same file = same name and date.
     */
    private ObjectInfo realSizeOnFujiWifi(ObjectInfo info) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= options.maxAttemptsWithoutProgress; attempt++) {
            try {
                PtpSession s = session();
                FujiWifi fuji = s.fujiWifi();
                if (fuji == null) return info;
                ObjectInfo cur = locate(info);
                fuji.beginRealSizes();
                ObjectInfo now = s.getObjectInfo(cur.handle);
                if (!now.filename.equals(info.filename) || !now.captureDate.equals(info.captureDate)) return info;
                sizedGeneration = generation; // handle checked and original files on: download() reuses it
                sizedHandle = now.handle;
                sizedSize = now.size;
                sizedIdentity = identity(info);
                return now.size == info.size && now.handle == info.handle ? info : info.withHandle(now.handle).withSize(now.size);
            } catch (PtpResponseException e) {
                return info; // let the normal path report it
            } catch (IOException e) {
                last = e;
                dropSession();
                backoff(attempt);
            }
        }
        throw last;
    }

    private static void endRealSizesQuietly(FujiWifi fuji) {
        try {
            fuji.endRealSizes();
        } catch (IOException ignored) {
            // the file is complete; a broken link is noticed by the next call
        }
    }

    /** Handle valid in the current session; cheap when the session is the one that produced the listing. */
    private ObjectInfo locate(ObjectInfo info) throws IOException {
        session();
        if (generation == listedGeneration) return info;
        if (remapGeneration == generation) {
            Long h = remap.get(identity(info));
            if (h == null) throw new IOException(info.filename + " is no longer on the camera");
            return h == info.handle ? info : info.withHandle(h);
        }
        return ensureHandle(info);
    }

    private static String identity(ObjectInfo i) {
        return i.filename + "|" + i.captureDate;
    }

    /** PTP reports 0xFFFFFFFF for files of 4 GiB or more: ask for the real 64-bit ObjectSize. */
    private ObjectInfo resolveLargeSize(ObjectInfo info) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= options.maxAttemptsWithoutProgress; attempt++) {
            try {
                return info.withSize(session().getObjectSize64(info.handle));
            } catch (IOException e) {
                last = e;
                dropSession();
                backoff(attempt);
            }
        }
        throw new IOException("Cannot read the size of " + info.filename + ": " + last.getMessage(), last);
    }

    private int noteFailure(ObjectInfo info, long have, long lastProgress, int failures, IOException e, Listener l)
            throws IOException {
        failures = have > lastProgress ? 1 : failures + 1; // progress resets the counter
        if (failures > options.maxAttemptsWithoutProgress) {
            throw new IOException("Giving up on " + info.filename + " after " + (failures - 1)
                    + " failed attempts: " + e.getMessage(), e);
        }
        if (l != null) l.onRetry(info, failures, e);
        return failures;
    }

    private void transferMore(ObjectInfo info, FileChannel out, long total, Listener l) throws IOException {
        PtpSession s = session();
        int chunk = s.fujiWifi() != null ? Math.min(options.chunkSize, FujiWifi.MAX_PARTIAL) : options.chunkSize;
        if (partialSupported && s.supports(Ptp.OC_GET_PARTIAL_OBJECT, true)) {
            {
                long offset = out.size();
                out.position(offset);
                OutputStream sink = new ProgressSink(out, offset, total, info, l, stats);
                while (offset < total) {
                    if (l != null && l.isCancelled()) throw new CancelledException();
                    long n = Math.min(chunk, total - offset);
                    if (offset + n > 0xFFFFFFFFL) {
                        s.getExtensionPartialObject(info.handle, offset, n, sink); // 64-bit offset
                    } else {
                        s.getPartialObject(info.handle, offset, n, sink);
                    }
                    long now = out.position();
                    stats.chunks++;
                    long wait = s.lastFirstDataNanos();
                    if (wait > 0) stats.waitNanos += wait;
                    if (now == offset) {
                        throw new IOException("Empty chunk at " + offset + " of " + total);
                    }
                    // PTP lets GetPartialObject send less than asked; the X-M5 does it on videos (1 MiB - 32 bytes).
                    // Not an error: carry on from where it stopped. Dropping the session here, mid-download, made
                    // the camera stop answering and turn its Wi-Fi off.
                    offset = now;
                }
            }
        } else {
            if (total > 0xFFFFFFFFL) {
                throw new IOException(info.filename + " needs partial transfers (>= 4 GiB) but the camera has none");
            }
            out.truncate(0);
            out.position(0);
            s.getObject(info.handle, new ProgressSink(out, 0, total, info, l, stats));
        }
    }

    /** After a reconnect, make sure the handle still points at the same file; otherwise re-find it. */
    private ObjectInfo ensureHandle(ObjectInfo info) throws IOException {
        PtpSession s = session();
        FujiWifi fuji = s.fujiWifi();
        // Fuji Wi-Fi reports a placeholder size unless asked for real sizes, and a new session starts without
        if (fuji != null) fuji.beginRealSizes();
        try {
            ObjectInfo now = s.getObjectInfo(info.handle);
            if (now.sameFileAs(info)) return now.handle == info.handle ? info : info.withHandle(now.handle);
        } catch (PtpResponseException e) {
            if (e.getResponseCode() != Ptp.RC_INVALID_OBJECT_HANDLE) throw e;
        }
        List<ObjectInfo> all = ObjectLister.listAll(s, null, null, listRealSizes, listBatch);
        if (fuji != null) fuji.beginRealSizes(); // the listing switched it back off
        Map<String, Long> map = new HashMap<>();
        for (ObjectInfo c : all) map.put(identity(c), c.handle);
        remap = map;
        remapGeneration = generation; // later lookups in this session skip the re-listing
        for (ObjectInfo candidate : all) {
            if (candidate.sameFileAs(info)) return info.withHandle(candidate.handle);
        }
        throw new IOException(info.filename + " is no longer on the camera");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^0-9A-Za-z]", "");
    }

    private void backoff(int attempt) throws IOException {
        long ms = Math.min(options.backoffMaxMs, options.backoffBaseMs << Math.min(attempt - 1, 6));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    /** Writes to the download's channel and reports progress; throttled so UI updates stay cheap. */
    private static final class ProgressSink extends OutputStream {
        private final FileChannel out;
        private final long total;
        private final ObjectInfo info;
        private final Listener listener;
        private final Stats stats;
        private long done;
        private long lastReport;

        ProgressSink(FileChannel out, long start, long total, ObjectInfo info, Listener l, Stats stats) {
            this.out = out;
            this.total = total;
            this.info = info;
            this.listener = l;
            this.stats = stats;
            this.done = start;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b, off, len);
            long w0 = System.nanoTime();
            while (bb.hasRemaining()) out.write(bb);
            stats.writeNanos += System.nanoTime() - w0;
            done += len;
            if (listener != null && (done - lastReport >= 256 * 1024 || done == total)) {
                lastReport = done;
                listener.onProgress(info, done, total);
            }
        }
    }
}
