package com.fujixfer.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fuji session set-up on the Wi-Fi command pipe, after the 82-byte handshake.
 *
 * A Fuji body over Wi-Fi is not a plain PTP responder: after OpenSession it waits for the client to say
 * which function it wants (ClientState, prop 0xDF01) and, until then, it ignores ordinary requests and
 * eventually shows "not found, please check the app and select that function again". The sequence here
 * is the one XApp uses for image import (CameraConnectModel.setFunctionMode(20, 0xDF28, 3) and
 * ImportImageModel.setImageViewFunctionMode) and the one fudge implements in fuji_setup():
 *
 * <ol>
 *   <li>50 ms pause, OpenSession (session 1, transaction 1), no GetDeviceInfo;</li>
 *   <li>poll the event list (prop 0xD212) until CameraState (0xDF00) leaves 0 = waiting for access;</li>
 *   <li>read the version props 0xDF22, 0xDF25, 0xDF21, 0xDF24;</li>
 *   <li>set ClientState 0xDF01: 20 (remote image view, XApp) after a Bluetooth hand-off on recent bodies,
 *       otherwise the mode fudge derives from CameraState;</li>
 *   <li>XApp gallery set-up: echo 0xDF28, 0xD226 = 0, 0xD227 = 0, then 0x9054, 0x9055, 0x9050, 0xD22B,
 *       0x9053(0, 30000), 0xD620, 0xD621 (results ignored, as fudge does);</li>
 *   <li>older bodies: version props and, in remote mode, a short open/close capture with the two extra ports.</li>
 * </ol>
 *
 * Object handles on Wi-Fi are 1..ObjectCount (prop 0xD222), not GetObjectHandles. File sizes in
 * ObjectInfo are only real after 0xD226 = 2 (XApp mode) or 0xD227 = 1 (older mode), see beginRealSizes.
 */
public final class FujiWifi {
    // Device properties (names from fudge's fujiptp.h)
    static final int DPC_EVENTS = 0xD212;
    static final int DPC_SELECTED_IMGS_MODE = 0xD220;
    static final int DPC_OBJECT_COUNT = 0xD222;
    static final int DPC_COMPRESS_SMALL = 0xD226;
    /** Size of the copies the camera makes in mode 0xD226 = 1 (XApp's ImageResizeRateValue): 0 XS, 1 S, 0xFFFF none. */
    static final int DPC_COMPRESS_RATE = 0xD22E;
    static final int DPC_CORRECT_FILE_SIZE = 0xD227;
    static final int DPC_UNKNOWN_D22B = 0xD22B;
    static final int DPC_IMPORT_OBJECT_COUNT = 0xD620;
    static final int DPC_IMPORT_OBJECT_HANDLES = 0xD621;
    static final int DPC_CAMERA_STATE = 0xDF00;
    static final int DPC_CLIENT_STATE = 0xDF01;
    static final int DPC_IMAGE_GET_VERSION = 0xDF21;
    static final int DPC_GET_OBJECT_VERSION = 0xDF22;
    static final int DPC_AUTOSAVE_VERSION = 0xDF23;
    static final int DPC_REMOTE_VERSION = 0xDF24;
    static final int DPC_REMOTE_GET_OBJECT_VERSION = 0xDF25;
    static final int DPC_PHOTO_VIEW_EX_VERSION = 0xDF28;

    // CameraState (0xDF00)
    static final int STATE_WAIT_FOR_ACCESS = 0;
    static final int STATE_MULTIPLE_TRANSFER = 1;
    static final int STATE_FULL_ACCESS = 2;
    static final int STATE_PC_AUTO_SAVE = 3;
    static final int STATE_REMOTE_ACCESS = 6;

    // ClientState (0xDF01)
    static final int MODE_VIEW_MULTIPLE = 1;
    static final int MODE_VIEW_ALL_IMGS = 2;
    static final int MODE_OLD_REMOTE = 3;
    static final int MODE_REMOTE = 5;
    static final int MODE_REMOTE_IMG_VIEW = 11;
    static final int MODE_REMOTE_IMG_VIEW_XAPP = 20;

    static final long REMOTE_VERSION_XAPP_MIN = 0x20007L;
    static final long CONNECT_REMOTE_VERSION = 0x2000CL;
    /** fudge's FUJI_MAX_PARTIAL_OBJECT: largest GetPartialObject request it sends over Wi-Fi. */
    public static final int MAX_PARTIAL = 0x100000;
    static final int EVENT_PORT = 55741;
    static final int LIVEVIEW_PORT = 55742;
    /** Object XApp touches with 0x9054 / 0x9055 when it opens the gallery. */
    static final long XAPP_PROBE_HANDLE = 0x10000001L;

    public static final class Options {
        /** Fuji bodies need at least 50 ms between the handshake and OpenSession (fudge). */
        public int settleMs = 50;
        /** True when the camera's Wi-Fi was started over Bluetooth (XApp hand-off), false for the camera menu. */
        public boolean bluetoothHandoff = true;
        /** How long to wait for CameraState to leave "waiting for access". */
        public int accessWaitMs = 60000;
        public int eventPollMs = 100;
        /** The camera may show a dialog before it answers the ClientState change (fudge waits 255 s). */
        public int clientStateWaitMs = 120000;
        /** Extra ports opened once in the older remote mode (fudge: event 55741, live view 55742). */
        public int eventPort = EVENT_PORT;
        public int liveViewPort = LIVEVIEW_PORT;
    }

    /** Status callbacks; every method may be called from the connecting thread. */
    public interface Progress {
        /** Detail for the diagnostic log. */
        void trace(String message);

        /** CameraState still says the camera has not granted access after a short wait. */
        void waitingForAccess();

        /** The camera has not answered the function change after a few seconds. */
        void slowModeChange();
    }

    private static final Progress SILENT = new Progress() {
        @Override
        public void trace(String message) {
        }

        @Override
        public void waitingForAccess() {
        }

        @Override
        public void slowModeChange() {
        }
    };

    private final FujiTcpLink link;
    private final PtpSession s;
    private final Options o;
    private final Progress p;

    private volatile int cameraState = -1;
    private volatile int numObjects = -1;
    private volatile int selectedImgsMode = -1;
    private long getObjectVersion = -1;
    private long remoteGetObjectVersion = -1;
    private long imageGetVersion = -1;
    private long remoteVersion = -1;
    private long photoViewExVersion = -1;
    private boolean xapp;
    private int clientMode = -1;

    private FujiWifi(FujiTcpLink link, PtpSession s, Options o, Progress p) {
        this.link = link;
        this.s = s;
        this.o = o;
        this.p = p == null ? SILENT : p;
    }

    /** Runs the whole set-up on a freshly handshaken link and attaches the result to the session. */
    public static FujiWifi setup(FujiTcpLink link, PtpSession session, Options o, Progress p) throws IOException {
        FujiWifi f = new FujiWifi(link, session, o == null ? new Options() : o, p);
        f.run();
        session.attach(f);
        return f;
    }

    private void run() throws IOException {
        sleep(o.settleMs);
        s.openSession(false);
        p.trace("OpenSession ok");

        boolean events = pollEvents();
        if (!events) p.trace("lista eventi 0xD212 non disponibile");
        if (events && waitingForAccess()) {
            long start = System.currentTimeMillis();
            boolean told = false;
            while (waitingForAccess()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > o.accessWaitMs) {
                    throw new IOException("La camera non ha dato accesso alle foto (stato " + cameraState + ")");
                }
                if (!told && elapsed >= 1500) {
                    told = true;
                    p.waitingForAccess();
                }
                sleep(o.eventPollMs);
                if (!pollEvents()) break;
            }
        }
        p.trace("stato camera 0x" + Integer.toHexString(cameraState) + ", oggetti " + numObjects);

        getObjectVersion = prop(DPC_GET_OBJECT_VERSION);
        remoteGetObjectVersion = prop(DPC_REMOTE_GET_OBJECT_VERSION);
        imageGetVersion = prop(DPC_IMAGE_GET_VERSION);
        remoteVersion = prop(DPC_REMOTE_VERSION);
        xapp = o.bluetoothHandoff && remoteVersion >= REMOTE_VERSION_XAPP_MIN;
        p.trace(String.format("versioni DF22=%s DF25=%s DF21=%s DF24=%s → modo %s", hex(getObjectVersion),
                hex(remoteGetObjectVersion), hex(imageGetVersion), hex(remoteVersion), xapp ? "XApp" : "classico"));

        clientMode = chooseMode();
        p.trace("ClientState 0xDF01 <- " + clientMode);
        link.expectSlowReply(o.clientStateWaitMs, p::slowModeChange);
        try {
            s.setProp16(DPC_CLIENT_STATE, clientMode);
        } finally {
            link.endSlowReply();
        }
        pollEvents();

        if (xapp) configureXappGallery();
        if (cameraState == STATE_MULTIPLE_TRANSFER) return;
        if (!xapp) {
            configureVersion();
            if (remoteVersion != -1 && cameraState == STATE_REMOTE_ACCESS) {
                openAndCloseRemote();
                configureRemoteGallery();
            }
        }
        p.trace("sessione pronta, oggetti " + numObjects);
    }

    private boolean waitingForAccess() {
        return cameraState == STATE_WAIT_FOR_ACCESS || cameraState == -1;
    }

    /** fudge's fuji_config_init_mode. */
    private int chooseMode() {
        if (xapp) return MODE_REMOTE_IMG_VIEW_XAPP;
        if (remoteVersion != -1 || cameraState == STATE_REMOTE_ACCESS) return MODE_REMOTE;
        switch (cameraState) {
            case STATE_MULTIPLE_TRANSFER: return MODE_VIEW_MULTIPLE;
            case STATE_PC_AUTO_SAVE: return MODE_OLD_REMOTE;
            default: return MODE_VIEW_ALL_IMGS;
        }
    }

    /** fudge's fuji_xapp_config_gallery: what XApp sends when it opens the gallery. */
    private void configureXappGallery() throws IOException {
        photoViewExVersion = prop(DPC_PHOTO_VIEW_EX_VERSION);
        if (photoViewExVersion >= 0) quiet(() -> s.setProp32(DPC_PHOTO_VIEW_EX_VERSION, photoViewExVersion));
        quiet(() -> s.setProp16(DPC_COMPRESS_SMALL, 0));
        quiet(() -> s.setProp16(DPC_CORRECT_FILE_SIZE, 0));
        traceRc("0x9054", s.call(Ptp.OC_FUJI_GET_EXT_OBJECT_INFO, XAPP_PROBE_HANDLE));
        traceRc("0x9055", s.call(Ptp.OC_FUJI_GET_EXT_THUMB, XAPP_PROBE_HANDLE));
        traceRc("0x9050", s.call(Ptp.OC_FUJI_GET_IMPORT_FOLDERS));
        prop(DPC_UNKNOWN_D22B);
        readDateGroups();
        long importCount = prop(DPC_IMPORT_OBJECT_COUNT);
        traceImportHandles(importCount);
        p.trace("galleria XApp configurata (DF28=" + hex(photoViewExVersion) + ")");
    }

    /** One day of the card as 0x9053 reports it: the date string the camera sends and how many files. */
    public static final class DateGroup {
        public final String date;
        public final int count;

        DateGroup(String date, int count) {
            this.date = date;
            this.count = count;
        }
    }

    private final List<DateGroup> dateGroups = new ArrayList<>();

    /** Files per day from the gallery set-up (empty if the camera did not answer or the reply did not parse). */
    public List<DateGroup> dateGroups() {
        return dateGroups;
    }

    /**
     * 0x9053 GetSpecifiedObjectCountGroupByDate(offset, count), as XApp sends it (0, 30000). Layout from XApp's
     * libFTLPTPIP (FTL_PTP_GetSpecifiedObjectCountGroupByDate): u32 number of days, then for each day a u32
     * (not used by XApp), a PTP string with the date, and a u32 file count. XApp then splits its handle list
     * into days by these counts, in order, and reads each file's details only when it is shown.
     */
    private void readDateGroups() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            PtpSession.Response r = s.transact(Ptp.OC_FUJI_GET_IMPORT_DATES, new long[]{0, 30000}, out);
            byte[] d = out.toByteArray();
            if (r.code != Ptp.RC_OK) {
                p.trace("0x9053 -> " + Ptp.rcName(r.code));
                return;
            }
            List<DateGroup> g = parseDateGroups(d);
            if (g == null) {
                p.trace("0x9053: risposta non decodificata, " + d.length + " byte: " + hexHead(d, 64));
                return;
            }
            dateGroups.addAll(g);
            long sum = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.size(); i++) {
                sum += g.get(i).count;
                if (i < 5) sb.append(i == 0 ? "" : ", ").append(g.get(i).date).append(" ×").append(g.get(i).count);
            }
            p.trace("file per giorno (0x9053): " + g.size() + " giorni, " + sum + " file; " + sb
                    + (g.size() > 5 ? ", …" : "") + " · inizio risposta " + hexHead(d, 24));
        } catch (IOException e) {
            p.trace("0x9053 non riuscito: " + e.getMessage());
        }
    }

    static List<DateGroup> parseDateGroups(byte[] d) {
        if (d.length < 4) return null;
        long n = Ptp.u32(d, 0);
        if (n > 100000) return null;
        List<DateGroup> g = new ArrayList<>();
        int pos = 4;
        for (long i = 0; i < n; i++) {
            if (pos + 5 > d.length) return null;
            pos += 4; // u32 XApp skips
            int len = d[pos] & 0xFF;
            pos += 1;
            if (pos + 2 * len + 4 > d.length) return null;
            StringBuilder date = new StringBuilder();
            for (int k = 0; k < len; k++) {
                char c = (char) Ptp.u16(d, pos + 2 * k);
                if (c != 0) date.append(c);
            }
            pos += 2 * len;
            long count = Ptp.u32(d, pos);
            pos += 4;
            g.add(new DateGroup(date.toString(), (int) count));
        }
        return g;
    }

    private final List<Long> importHandles = new ArrayList<>();

    /**
     * The handles in the camera's gallery order (newest day first), from 0xD621; XApp splits this list into
     * days with the 0x9053 counts, in order. Empty if the camera did not give it.
     */
    public List<Long> importHandles() {
        return importHandles;
    }

    /** 0xD621: u32 count, then that many u32 handles (seen on the X-M5: 1784 bytes for 445 files). */
    static List<Long> parseHandleArray(byte[] v) {
        if (v.length < 4) return null;
        long n = Ptp.u32(v, 0);
        if (n > 100000 || 4 + 4 * n != v.length) return null;
        List<Long> out = new ArrayList<>((int) n);
        for (int i = 0; i < n; i++) out.add(Ptp.u32(v, 4 + 4 * i));
        return out;
    }

    private void traceImportHandles(long count) {
        try {
            byte[] v = s.getPropValue(DPC_IMPORT_OBJECT_HANDLES);
            List<Long> h = parseHandleArray(v);
            if (h != null) importHandles.addAll(h);
            p.trace("0xD620 = " + count + ", 0xD621: " + (h == null ? v.length + " byte non decodificati " + hexHead(v, 32)
                    : h.size() + " file in ordine di galleria, primi " + h.subList(0, Math.min(6, h.size()))));
        } catch (IOException e) {
            p.trace("0xD621 non letto: " + e.getMessage());
        }
    }

    private static String hexHead(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, d.length); i++) sb.append(String.format("%02x", d[i] & 0xFF));
        if (d.length > max) sb.append("…");
        return sb.toString();
    }

    /** fudge's fuji_config_version_ for the non-XApp path. */
    private void configureVersion() throws IOException {
        if (cameraState == STATE_PC_AUTO_SAVE) {
            long v = prop(DPC_AUTOSAVE_VERSION);
            if (v >= 0) s.setProp32(DPC_AUTOSAVE_VERSION, v);
        } else if (remoteVersion == -1) {
            long v = prop(DPC_GET_OBJECT_VERSION);
            if (v >= 0) s.setProp32(DPC_GET_OBJECT_VERSION, v);
        } else {
            s.setProp32(DPC_REMOTE_VERSION, CONNECT_REMOTE_VERSION);
        }
    }

    /**
     * fudge's fuji_config_liveview + fuji_end_liveview on the first connection: the camera stops
     * answering in remote mode unless capture is opened once (with its two extra sockets) and closed.
     */
    private void openAndCloseRemote() throws IOException {
        long tid = s.nextTransactionId();
        int rc = s.call(Ptp.OC_INITIATE_OPEN_CAPTURE, 0, 0);
        if (rc != Ptp.RC_OK) throw new PtpResponseException(Ptp.OC_INITIATE_OPEN_CAPTURE, rc);
        pollEvents();
        sleep(50);
        link.openSidePort(o.eventPort);
        link.openSidePort(o.liveViewPort);
        pollEvents();
        rc = s.call(Ptp.OC_TERMINATE_OPEN_CAPTURE, tid);
        if (rc != Ptp.RC_OK) throw new PtpResponseException(Ptp.OC_TERMINATE_OPEN_CAPTURE, rc);
    }

    /** fudge's fuji_config_image_gallery, non-XApp branch. */
    private void configureRemoteGallery() throws IOException {
        if (remoteGetObjectVersion == -1) return;
        pollEvents();
        s.setProp16(DPC_CAMERA_STATE, STATE_REMOTE_ACCESS);
        pollEvents();
        pollEvents();
        s.setProp16(DPC_CLIENT_STATE, MODE_REMOTE_IMG_VIEW);
        pollEvents();
        prop(DPC_REMOTE_GET_OBJECT_VERSION);
        s.setProp32(DPC_REMOTE_GET_OBJECT_VERSION, 5); // X-S10 and X-H1 want at least 4
        pollEvents();
    }

    // ------------------------------------------------------------------ used after set-up

    /** Reads the camera's change list (prop 0xD212). Returns false when the camera does not have one. */
    public boolean pollEvents() throws IOException {
        byte[] d;
        try {
            d = s.getPropValue(DPC_EVENTS);
        } catch (PtpResponseException e) {
            return false;
        }
        applyEvents(d);
        return true;
    }

    void applyEvents(byte[] d) {
        if (d.length < 2) return;
        int n = Ptp.u16(d, 0);
        for (int i = 0; i < n && 2 + 6 * i + 6 <= d.length; i++) {
            int code = Ptp.u16(d, 2 + 6 * i);
            long value = Ptp.u32(d, 4 + 6 * i);
            switch (code) {
                case DPC_CAMERA_STATE: cameraState = (int) value; break;
                case DPC_OBJECT_COUNT: numObjects = (int) value; break;
                case DPC_SELECTED_IMGS_MODE: selectedImgsMode = (int) value; break;
                default: break;
            }
        }
    }

    /** Object handles for this session: 1..ObjectCount (handle 0 is invalid in PTP). */
    public List<Long> handles() throws IOException {
        pollEvents(); // picks up shots taken since the set-up
        int n = numObjects;
        if (n < 0) {
            long v = prop(DPC_OBJECT_COUNT);
            n = v < 0 ? 0 : (int) v;
            numObjects = n;
        }
        List<Long> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) out.add((long) i);
        return out;
    }

    /**
     * Makes ObjectInfo report real sizes and GetPartialObject return the original file: 0xD226 = 2 in XApp
     * mode, 0xD227 = 1 otherwise (Fuji sets these before GetObjectInfo/GetPartialObject and resets after).
     */
    public void beginRealSizes() throws IOException {
        if (xapp) {
            quiet(() -> s.setProp16(DPC_COMPRESS_SMALL, 2));
        } else {
            pollEvents();
            link.expectSlowReply(15000, null);
            try {
                quiet(() -> s.setProp16(DPC_CORRECT_FILE_SIZE, 1));
            } finally {
                link.endSlowReply();
            }
        }
    }

    /**
     * After a transfer. XApp mode: nothing to send. This used to set 0xD226 = 2 again, the value
     * {@link #beginRealSizes} had just set, so it was one useless command per file; the mode stays on originals
     * and whatever comes next sets its own (next file: 2, end of the batch or listing: 0).
     */
    public void endRealSizes() throws IOException {
        if (xapp) {
            return;
        } else {
            pollEvents();
            quiet(() -> s.setProp16(DPC_CORRECT_FILE_SIZE, 0));
        }
    }

    /**
     * XApp's zoom view: the camera hands out reduced copies (size S) until {@link #resetSizeMode}. Not quiet,
     * unlike the gallery set-up: a refusal means there are no small copies, and the caller falls back.
     */
    public void beginSmallCopies() throws IOException {
        s.setProp16(DPC_COMPRESS_RATE, 1);
        s.setProp16(DPC_COMPRESS_SMALL, 1);
    }

    /** Back to the gallery state the set-up leaves (XApp: 0xD226 = 0; classic: 0xD227 = 0). */
    void resetSizeMode() throws IOException {
        if (xapp) quiet(() -> s.setProp16(DPC_COMPRESS_SMALL, 0));
        else quiet(() -> s.setProp16(DPC_CORRECT_FILE_SIZE, 0));
    }

    /** The next answers may take up to ms instead of the normal read timeout (see FujiTcpLink.expectSlowReply). */
    public void expectSlowReply(long ms) {
        link.expectSlowReply(ms, null);
    }

    public void endSlowReply() {
        link.endSlowReply();
    }

    /** True when the session runs like XApp (ClientState 20, Wi-Fi started over Bluetooth). */
    public boolean isXApp() {
        return xapp;
    }

    /** Recent bodies block GetThumb forever unless GetObjectInfo for that handle came right before (fudge). */
    public boolean needsInfoBeforeThumb() {
        return remoteVersion > 0x20006L;
    }

    /** Diagnostic line to the connection's log. */
    void trace(String message) {
        p.trace(message);
    }

    /** Recent bodies (0xDF25 >= 5) number objects oldest first, so new shots get the highest handles (fudge). */
    public boolean newestLast() {
        return remoteGetObjectVersion >= 5;
    }

    public boolean isXapp() {
        return xapp;
    }

    public int cameraState() {
        return cameraState;
    }

    public int objectCount() {
        return numObjects;
    }

    public long remoteVersion() {
        return remoteVersion;
    }

    public int clientMode() {
        return clientMode;
    }

    // ------------------------------------------------------------------ helpers

    private long prop(int code) throws IOException {
        long v = s.getPropInt(code);
        return v == 0xFFFFFFFFL ? -1 : v;
    }

    private interface Call {
        void run() throws IOException;
    }

    /** Camera answered with an error code: log and carry on, as fudge does for the gallery set-up. */
    private void quiet(Call c) throws IOException {
        try {
            c.run();
        } catch (PtpResponseException e) {
            p.trace("ignorato: " + e.getMessage());
        }
    }

    private void traceRc(String what, int rc) {
        if (rc != Ptp.RC_OK) p.trace(what + " -> " + Ptp.rcName(rc));
    }

    private static String hex(long v) {
        return v < 0 ? "?" : "0x" + Long.toHexString(v);
    }

    private static void sleep(long ms) throws IOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
