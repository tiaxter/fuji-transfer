package com.fujixfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fuji Bluetooth LE protocol as implemented by XApp 2.7.6 (com.fujifilm.xapp.model.bleconnect):
 * pairing, re-connection and the request that makes the camera switch its Wi-Fi access point on.
 *
 * Two generations exist. "Legacy" bodies advertise the file-transfer service and send a 4-byte pairing
 * key in the advertisement; the phone writes it back. Newer firmware ("RED", EU Radio Equipment
 * Directive) bonds at OS level and exchanges a 32-bit identification number instead.
 */
public final class FujiBle {
    private FujiBle() {
    }

    public static final int MANUFACTURER_ID = 0x04D8; // 1240
    public static final int MTU = 185;

    // Services seen in advertisements (used for scan filters and to tell generations apart)
    public static final String SVC_FILE_TRANSFER = "AF854C2E-B214-458E-97E2-912C4ECF2CB8";
    public static final String SVC_FILE_TRANSFER_XHALF = "462426E1-D712-4A28-BE62-61A786DAA866";
    public static final String SVC_FILE_TRANSFER_XHALF_RED = "CC74BD26-4ABB-4911-AE72-9CD93109A772";
    public static final String SVC_CAMERA_INFORMATION_RED = "A9D2B304-E8D6-4902-8336-352B772D7597";
    public static final String SVC_CONNECTED_DEVICE_INFORMATION_RED = "123D8F06-62A1-4935-9322-833C531EE225";
    public static final String SVC_CAMERA_INFORMATION_PCSH = "EB894E6E-A8F8-4B3B-9A30-9B2BBA546675";
    public static final String SVC_STARTUP_INFORMATION = "731893F9-744E-4899-B7E3-174106FF2B82";
    public static final String SVC_STARTUP_INFORMATION_RED = "804DAA8E-FFEB-4AB3-8E75-6EDD7303208D";
    public static final List<String> SCAN_SERVICES = Arrays.asList(SVC_FILE_TRANSFER, SVC_FILE_TRANSFER_XHALF,
            SVC_FILE_TRANSFER_XHALF_RED, SVC_CONNECTED_DEVICE_INFORMATION_RED, SVC_CAMERA_INFORMATION_RED,
            SVC_CAMERA_INFORMATION_PCSH, SVC_STARTUP_INFORMATION, SVC_STARTUP_INFORMATION_RED);

    // Services that hold the characteristics. The same characteristic UUID can exist in a legacy service and
    // in its RED counterpart on the same body: XApp always picks by generation (BTCameraService.SERVICE_MAP*).
    public static final String SVC_DEVICE_INFORMATION = "0000180A-0000-1000-8000-00805F9B34FB";
    public static final String SVC_CAMERA_SETTING = "4E941240-D01D-46B9-A5EA-67636806830B";
    public static final String SVC_CAMERA_STATE = "4C0020FE-F3B6-40DE-ACC9-77D129067B14";
    public static final String SVC_CAMERA_CONTROL = "6514EB81-4E8F-458D-AA2A-E691336CDFAC";
    public static final String SVC_CONNECTED_DEVICE_INFORMATION = "91F1DE68-DFF6-466E-8B65-FF13B0F16FB8";
    public static final String SVC_CAMERA_INFORMATION = "117C4142-EDD4-4C77-8696-DD18EEBB770A";

    // Characteristics
    public static final String CH_SERIAL_NUMBER = "00002A25-0000-1000-8000-00805F9B34FB";
    public static final String CH_PAIRING_KEY = "ABA356EB-9633-4E60-B73F-F52516DBD671";
    public static final String CH_DEVICE_NAME = "85B9163E-62D1-49FF-A6F5-054B4630D4A1";
    public static final String CH_IDENTIFICATION_NUMBER = "F557D96B-8284-4667-8793-B971C1DECA2A";
    public static final String CH_APP_INFO = "8B5ECF55-FC6B-40D0-B4C1-76F64E5453C7";
    public static final String CH_FUNCTION_LAUNCH_REQUEST = "600655E6-3637-42F1-8FB2-44EFC5C63B13";
    public static final String CH_AP_STATE = "A68E3F66-0FCC-4395-8D4C-AA980B5877FA";
    public static final String CH_STATE_ERROR_DETAILS = "1587B102-0B6D-4B63-9226-66FCC6D17387";
    public static final String CH_SSID = "BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4";
    public static final String CH_PASSPHRASE = "E809256A-915C-4967-92E8-53B7D4CAD213";
    public static final String CH_MAC_ADDRESS = "49A12959-DFAA-4EB2-89CE-62548AD948F3";
    public static final String CH_DISCONNECTED_REASON = "7EDE1988-B27E-43FC-80F4-6FEC994F0552";
    public static final String CH_IMAGE_RECEIVE_STATE = "A80BE3F8-8BCB-4ADD-A725-170B7A53ADC9";

    /** FunctionLaunchRequestType: ImportImage=1, InCameraViewing=3 (browse from the phone), RemoteShooting=4. */
    // Characteristics XApp subscribes to / writes before the Wi-Fi request (seen in the phone's Bluetooth
    // stack log while XApp imported from an X-M5; names from XApp's BTConstans)
    public static final String SVC_CURRENT_TIME = "E872B11F-D526-4AE1-9BB4-89A99D48FA59";
    public static final String CH_TRANSFER_STATE = "BD17BA04-B76B-4892-A545-B73BA1F74DAE";
    public static final String CH_DATE_SYNC_STATE = "F9150137-5D40-4801-A8DC-F7FC5B01DA50";
    public static final String CH_LOCATION_SYNC_STATE = "AD06C7B7-F41A-46F4-A29A-712055319122";
    public static final String CH_CAMERA_VITAL_STATE = "E6692C5C-B7CD-44F4-95FC-EDA07CE32560";
    public static final String CH_REMOTE_BOOT_SETTING = "7170FD5A-56D9-4C19-B043-7A7047D8E1A0";
    public static final String CH_LOGGING_SETTING = "75823784-FBB7-4B71-ABAE-CD9A34072E3C";
    public static final String CH_IMAGE_TRANSFER_SETTING_EX = "98934B2C-756C-4632-AA2F-DCBA1BFEC824";
    public static final String CH_LOCATION_SYNC_SETTING = "AAB609C4-94DD-4D89-BC60-665D5090B828";
    public static final String CH_IMAGE_RESIZE_SETTING = "82A9F452-C5CE-4EF5-8203-3FC9A47F8171";
    public static final String CH_LOCATION_SYNC_CYCLE = "C95D91AE-B247-4D6D-8661-7DD5D6A0F85B";
    public static final String CH_UTC_AND_TIMEZONE = "C52EDBCE-1FE2-4ECC-9483-907E6592BE9E";

    public static final int LAUNCH_IN_CAMERA_VIEWING = 3;

    public static final int AP_NOT_LAUNCHED = 0x8000;
    public static final int AP_LAUNCHED = 0x8001;
    public static final int AP_LAUNCHING = 0x8002;
    public static final int AP_LAUNCHED_RESERVED_TRANSFER = 0x8003;

    public static final int ERR_CAMERA_BUSY_SHORT = 2;
    /** Pause before repeating a launch request the camera answered with "busy for a moment" (XApp: 200 ms). */
    static final int BUSY_RETRY_MS = 200;
    /** Quick repeats (a few hundred ms apart each) before falling back to the slower check; about 6 s. */
    static final int MAX_QUICK_BUSY_RETRIES = 15;
    /** XApp ORs this into the camera's number before storing and writing it back. */
    public static final long XAPP_IDENTIFIER = 0x20000000L;

    public interface Log {
        /** Short status for the person using the app. */
        void log(String message);

        /** Technical detail for the diagnostic log. */
        default void trace(String message) {
        }
    }

    /** Service that holds a characteristic for the given generation, as in XApp's service maps. */
    public static String serviceOf(String ch, boolean red) {
        switch (ch) {
            case CH_SERIAL_NUMBER:
                return SVC_DEVICE_INFORMATION;
            case CH_SSID:
            case CH_PASSPHRASE:
                return SVC_CAMERA_SETTING;
            case CH_AP_STATE:
            case CH_STATE_ERROR_DETAILS:
                return SVC_CAMERA_STATE;
            case CH_FUNCTION_LAUNCH_REQUEST:
                return SVC_CAMERA_CONTROL;
            case CH_MAC_ADDRESS:
                return red ? SVC_CAMERA_INFORMATION_RED : SVC_CAMERA_INFORMATION;
            case CH_PAIRING_KEY:
            case CH_DEVICE_NAME:
            case CH_APP_INFO:
            case CH_IDENTIFICATION_NUMBER:
            case CH_DISCONNECTED_REASON:
            case CH_IMAGE_RECEIVE_STATE:
                return red ? SVC_CONNECTED_DEVICE_INFORMATION_RED : SVC_CONNECTED_DEVICE_INFORMATION;
            default:
                throw new IllegalArgumentException(ch);
        }
    }

    static String label(String ch) {
        switch (ch) {
            case CH_SERIAL_NUMBER: return "numero di serie";
            case CH_PAIRING_KEY: return "chiave di abbinamento";
            case CH_DEVICE_NAME: return "nome del telefono";
            case CH_IDENTIFICATION_NUMBER: return "numero di identificazione";
            case CH_APP_INFO: return "informazioni app";
            case CH_FUNCTION_LAUNCH_REQUEST: return "richiesta Wi-Fi";
            case CH_AP_STATE: return "stato Wi-Fi";
            case CH_STATE_ERROR_DETAILS: return "dettagli errore";
            case CH_SSID: return "nome rete";
            case CH_PASSPHRASE: return "password rete";
            case CH_MAC_ADDRESS: return "indirizzo MAC";
            default: return ch;
        }
    }

    // ------------------------------------------------------------------ advertisement

    /** What a scan result tells us about a Fuji body. */
    public static final class Advert {
        public final String name;
        public final String address;
        public final boolean red;
        public final boolean pairingMode;
        /** Legacy only: key to write back while pairing (4 bytes), null otherwise. */
        public final byte[] pairingKey;
        /** Legacy: first 4 characters of the name; RED: 5 characters from the manufacturer data. May be "". */
        public final String shortSerial;

        Advert(String name, String address, boolean red, boolean pairingMode, byte[] pairingKey, String shortSerial) {
            this.name = name;
            this.address = address;
            this.red = red;
            this.pairingMode = pairingMode;
            this.pairingKey = pairingKey;
            this.shortSerial = shortSerial;
        }

        /**
         * @param manufacturerData payload for company 0x04D8 (without the company id), or null
         * @param serviceUuids advertised service UUIDs, any case
         * @return null when this is not a Fuji camera the protocol can handle
         */
        public static Advert parse(String name, String address, byte[] manufacturerData, List<String> serviceUuids) {
            if (name == null) name = "";
            byte[] md = manufacturerData == null ? new byte[0] : manufacturerData;
            boolean legacy = containsUuid(serviceUuids, SVC_FILE_TRANSFER);
            if (legacy) {
                if (md.length == 0) {
                    return new Advert(name, address, false, false, null, name.length() >= 4 ? name.substring(0, 4) : "");
                }
                if (md[0] != 2 || md.length < 5) return null; // XApp ignores other data types
                byte[] key = Arrays.copyOfRange(md, 1, 5);
                return new Advert(name, address, false, true, key, name.length() >= 4 ? name.substring(0, 4) : "");
            }
            if (manufacturerData == null) return null; // neither generation
            String serial = "";
            if (md.length > 0) {
                if (md.length != 6 || md[0] != 1) return null;
                serial = new String(md, 1, 5, StandardCharsets.US_ASCII);
            }
            boolean pairing = containsUuid(serviceUuids, SVC_CAMERA_INFORMATION_RED);
            return new Advert(name, address, true, pairing, null, serial);
        }
    }

    // ------------------------------------------------------------------ stored pairing

    public static final class Pairing {
        public final boolean red;
        public final String address;
        public final String name;
        public final String shortSerial;
        public final byte[] pairingKey;
        public final long identificationNumber;

        public Pairing(boolean red, String address, String name, String shortSerial, byte[] pairingKey,
                       long identificationNumber) {
            this.red = red;
            this.address = address == null ? "" : address;
            this.name = name == null ? "" : name;
            this.shortSerial = shortSerial == null ? "" : shortSerial;
            this.pairingKey = pairingKey;
            this.identificationNumber = identificationNumber;
        }

        /** Same camera? RED bodies are found by their Bluetooth address, legacy ones by serial prefix. */
        public boolean matches(Advert a) {
            if (a.red != red) return false;
            if (!address.isEmpty() && address.equalsIgnoreCase(a.address)) return true;
            return !red && !shortSerial.isEmpty() && shortSerial.equals(a.shortSerial);
        }

        public String serialize() {
            return "v1;" + (red ? 1 : 0) + ";" + address + ";" + shortSerial.replace(";", "") + ";"
                    + (pairingKey == null ? "" : hex(pairingKey)) + ";" + identificationNumber + ";" + name;
        }

        public static Pairing parse(String s) {
            if (s == null) return null;
            String[] f = s.split(";", 7);
            if (f.length != 7 || !"v1".equals(f[0])) return null;
            try {
                return new Pairing("1".equals(f[1]), f[2], f[6], f[3], f[4].isEmpty() ? null : unhex(f[4]),
                        Long.parseLong(f[5]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static final class WifiCredentials {
        public final String ssid;
        public final String passphrase;
        /** "AA:BB:CC:DD:EE:FF" or "" when the camera did not tell. */
        public final String bssid;

        public WifiCredentials(String ssid, String passphrase, String bssid) {
            this.ssid = ssid;
            this.passphrase = passphrase;
            this.bssid = bssid;
        }
    }

    // ------------------------------------------------------------------ procedures

    /**
     * Characteristic access for one generation: picks the right service, retries like XApp (3 attempts),
     * and bonds when the camera wants an encrypted link.
     */
    static final class Cam {
        final GattLink g;
        final boolean red;
        final Log log;

        Cam(GattLink g, boolean red, Log log) {
            this.g = g;
            this.red = red;
            this.log = log;
        }

        boolean has(String ch) {
            return g.has(serviceOf(ch, red), ch);
        }

        byte[] read(String ch) throws IOException {
            return op(ch, null);
        }

        void write(String ch, byte[] v) throws IOException {
            op(ch, v);
        }

        private byte[] op(String ch, byte[] v) throws IOException {
            String svc = serviceOf(ch, red);
            String what = (v == null ? "Lettura " : "Scrittura ") + label(ch);
            GattLink.GattException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (v == null) {
                        byte[] r = g.read(svc, ch);
                        log.trace("R " + label(ch) + " = " + (ch.equals(CH_PASSPHRASE) ? "***" : hex(r)));
                        return r;
                    }
                    log.trace("W " + label(ch) + " <- " + hex(v));
                    g.write(svc, ch, v);
                    return null;
                } catch (GattLink.GattException e) {
                    last = e;
                    log.trace(what + " fallita, stato GATT " + e.getStatus() + " (tentativo " + attempt + ")");
                    if (attempt == 3) break;
                    if (!g.isBonded() && (e.needsBonding() || (red && e.getStatus() == 133))) {
                        bond(this);
                    } else {
                        g.sleep(500);
                    }
                }
            }
            throw new GattLink.GattException(what, last.getStatus());
        }

        int readU16(String ch) throws IOException {
            byte[] v = read(ch);
            return v.length >= 2 ? Ptp.u16(v, 0) : -1;
        }

        /** Steps XApp performs but that are not essential: failures are logged, not fatal. */
        void tryWrite(String ch, byte[] v) {
            if (!has(ch)) {
                log.trace("assente: " + label(ch));
                return;
            }
            try {
                write(ch, v);
            } catch (IOException e) {
                log.trace("ignoro: " + e.getMessage());
            }
        }
    }

    private static void bond(Cam c) throws IOException {
        c.log.log("Conferma l'abbinamento Bluetooth sul telefono e sulla camera…");
        c.g.requestBond();
        for (int i = 0; i < 60 && !c.g.isBonded(); i++) c.g.sleep(500);
        c.log.trace(c.g.isBonded() ? "bonding completato" : "bonding non completato entro 30 s");
    }

    /** First registration. The camera must be in "pairing" mode (Bluetooth settings on the body). */
    public static Pairing pair(GattLink g, Advert adv, String phoneName, Log log) throws IOException {
        if (!adv.pairingMode) throw new IOException("La camera non è in modalità abbinamento");
        log.trace("abbinamento " + (adv.red ? "RED" : "legacy") + " con " + adv.name + " " + adv.address);
        Cam c = new Cam(g, adv.red, log);
        if (adv.red) return redHandshake(c, adv.address, adv.name, 0, phoneName, true, 40);
        return legacyHandshake(c, adv.address, adv.name, adv.pairingKey, phoneName);
    }

    /** Every later connection to a camera paired before. Returns the record to keep (may be updated). */
    public static Pairing reconnect(GattLink g, Pairing p, String phoneName, Log log) throws IOException {
        log.trace("riconnessione " + (p.red ? "RED" : "legacy") + " con " + p.name + " " + p.address);
        Cam c = new Cam(g, p.red, log);
        if (p.red) return redHandshake(c, p.address, p.name, p.identificationNumber, phoneName, false, 40);
        if (p.pairingKey == null) {
            // Pairing owned by XApp: the key is in XApp's private storage. The link XApp opened is already
            // authenticated, so the camera may accept requests without it.
            log.trace("legacy senza chiave: uso il collegamento già autenticato");
            return p;
        }
        return legacyHandshake(c, p.address, p.name, p.pairingKey, phoneName);
    }

    /**
     * Uses the pairing that already exists between this phone and the camera (made by XApp), without
     * registering again. RED bodies: the phone is bonded at OS level for every app, and the number XApp
     * writes back is always (number read | 0x20000000), so writing the same value is equivalent.
     * Legacy bodies: the key cannot be recovered unless the camera lets us read it; the link already opened
     * by XApp is used as is.
     */
    public static Pairing adopt(GattLink g, String address, String name, String phoneName, Log log) throws IOException {
        if (!g.has(SVC_CAMERA_CONTROL, CH_FUNCTION_LAUNCH_REQUEST)) {
            throw new IOException("Il dispositivo " + name + " non sembra una fotocamera Fujifilm");
        }
        boolean red = g.has(SVC_CONNECTED_DEVICE_INFORMATION_RED, CH_IDENTIFICATION_NUMBER);
        log.trace("riuso abbinamento esistente, tipo " + (red ? "RED" : "legacy") + ", bonded=" + g.isBonded());
        Cam c = new Cam(g, red, log);
        if (red) {
            try {
                return redHandshake(c, address, name, 0, phoneName, false, 6);
            } catch (CameraNotConfirmedException e) {
                throw new IOException("La camera non risulta abbinata a questo telefono. Usa \"Abbina nuova camera\".");
            }
        }
        byte[] key = null;
        if (c.has(CH_PAIRING_KEY)) {
            try {
                byte[] v = c.read(CH_PAIRING_KEY);
                if (v.length == 4 && (v[0] | v[1] | v[2] | v[3]) != 0) key = v;
            } catch (IOException e) {
                log.trace("chiave non leggibile dalla camera (normale): " + e.getMessage());
            }
        }
        if (key != null) return legacyHandshake(c, address, name, key, phoneName);
        String shortSerial = name.length() >= 4 ? name.substring(0, 4) : "";
        return new Pairing(false, address, name, shortSerial, null, 0);
    }

    /** The camera never confirmed (identification number stayed 0). */
    public static final class CameraNotConfirmedException extends IOException {
        private static final long serialVersionUID = 1L;

        CameraNotConfirmedException() {
            super("La camera non ha confermato l'abbinamento. Conferma sul display della camera e riprova.");
        }
    }

    private static Pairing legacyHandshake(Cam c, String address, String name, byte[] key, String phoneName)
            throws IOException {
        String serial = readSerial(c);
        if (!c.has(CH_PAIRING_KEY)) throw new IOException("La camera non espone il servizio di abbinamento Bluetooth");
        c.log.log("Invio chiave di abbinamento…");
        c.write(CH_PAIRING_KEY, key);
        writeNameAndApp(c, phoneName);
        String shortSerial = name.length() >= 4 ? name.substring(0, 4) : "";
        return new Pairing(false, address, name, shortSerial.isEmpty() ? serial : shortSerial, key, 0);
    }

    private static Pairing redHandshake(Cam c, String address, String name, long storedId, String phoneName,
                                        boolean newPairing, int tries) throws IOException {
        GattLink g = c.g;
        String idSvc = serviceOf(CH_IDENTIFICATION_NUMBER, true);
        if (!c.has(CH_IDENTIFICATION_NUMBER)) {
            throw new IOException("La camera non espone il numero di identificazione Bluetooth");
        }
        // XApp skips this first write on some phone brands; it is not needed for the rest to work.
        if (newPairing) c.tryWrite(CH_DEVICE_NAME, nameBytes(phoneName));
        long id = 0;
        for (int i = 0; i < tries && id == 0; i++) { // XApp tries 20 times; people need time to tap OK
            byte[] v;
            try {
                v = g.read(idSvc, CH_IDENTIFICATION_NUMBER);
                c.log.trace("R numero di identificazione = " + hex(v));
            } catch (GattLink.GattException e) {
                c.log.trace("Lettura numero di identificazione: stato GATT " + e.getStatus());
                if (!e.needsBonding() && e.getStatus() != 133) throw e;
                if (!g.isBonded()) bond(c);
                else g.sleep(1000);
                continue;
            }
            long n = v.length >= 4 ? Ptp.u32(v, 0) : 0;
            if (n == 0) {
                if (i == 0) c.log.log("Conferma l'abbinamento sul display della camera…");
                g.sleep(1000);
                continue;
            }
            id = storedId != 0 ? storedId : (n | XAPP_IDENTIFIER) & 0xFFFFFFFFL;
        }
        if (id == 0) throw new CameraNotConfirmedException();
        byte[] out = new byte[4];
        Ptp.putU32(out, 0, id);
        c.write(CH_IDENTIFICATION_NUMBER, out);
        String serial = readSerial(c);
        writeNameAndApp(c, phoneName);
        return new Pairing(true, address, name, serial, null, id);
    }

    /**
     * Asks the camera to switch its access point on for browsing from the phone and returns how to join it.
     * Handles the "camera busy for a moment" answer by retrying, like XApp.
     */
    public static WifiCredentials launchWifi(GattLink g, boolean red, long timeoutMs, Log log) throws IOException {
        Cam c = new Cam(g, red, log);
        if (!c.has(CH_FUNCTION_LAUNCH_REQUEST)) {
            throw new IOException("Questa camera non accetta l'avvio del Wi-Fi via Bluetooth");
        }
        prepareLikeXApp(g, red, log);
        boolean hasState = c.has(CH_AP_STATE);
        if (hasState) {
            try {
                g.enableNotifications(serviceOf(CH_AP_STATE, red), CH_AP_STATE);
            } catch (IOException e) {
                log.trace("notifiche stato Wi-Fi non attive (" + e.getMessage() + "): leggo a intervalli");
            }
        }
        int state = hasState ? c.readU16(CH_AP_STATE) : -1;
        log.trace("stato Wi-Fi iniziale 0x" + Integer.toHexString(state));
        long requestAt = System.nanoTime();
        final long firstRequestAt = requestAt;
        if (!launched(state)) {
            log.log("Chiedo alla camera di accendere il Wi-Fi…");
            c.write(CH_FUNCTION_LAUNCH_REQUEST, u16(LAUNCH_IN_CAMERA_VIEWING));
        }
        if (!hasState) g.sleep(3000); // no way to observe it: give the access point time to come up
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        int busyRetries = 0;
        int quickRetries = 0;
        long lastRefusedAt = firstRequestAt;
        boolean acceptedLogged = false;
        int quietPolls = 0;
        boolean sawLaunching = false;
        while (hasState && !launched(state)) {
            if (System.nanoTime() > deadline) {
                throw new IOException("La camera non ha acceso il Wi-Fi entro " + timeoutMs / 1000 + " s");
            }
            byte[] v = g.awaitNotification(CH_AP_STATE, 1000);
            int before = state;
            state = v != null && v.length >= 2 ? Ptp.u16(v, 0) : c.readU16(CH_AP_STATE);
            if (state != before) log.trace("stato Wi-Fi 0x" + Integer.toHexString(state) + (v != null ? " (notifica)" : ""));
            if (state == AP_LAUNCHING) sawLaunching = true;
            long sinceRequest = (System.nanoTime() - requestAt) / 1_000_000L;
            // A request the camera turns down comes back at once as a "not launched" notification (X-M5: 70-110 ms
            // after the write, never seen after an accepted one). Right after the Bluetooth set-up it answers "busy
            // for a moment" (error 2) to the first request, every time (4 Nov, 6 connections out of 6; later requests
            // on the same link were accepted at the first try). Read the reason now and repeat after 200 ms, as XApp
            // does, instead of waiting for three quiet polls (2.5 s) to notice.
            if (v != null && state == AP_NOT_LAUNCHED && !sawLaunching && sinceRequest < 1000
                    && quickRetries < MAX_QUICK_BUSY_RETRIES) {
                int why = c.has(CH_STATE_ERROR_DETAILS) ? c.readU16(CH_STATE_ERROR_DETAILS) : 0;
                if (why == ERR_CAMERA_BUSY_SHORT) {
                    quickRetries++;
                    log.trace("richiesta rifiutata subito dopo " + sinceRequest + " ms (camera occupata, errore 2): riprovo tra "
                            + BUSY_RETRY_MS + " ms, tentativo " + (quickRetries + 1));
                    lastRefusedAt = requestAt;
                    g.sleep(BUSY_RETRY_MS);
                    requestAt = System.nanoTime(); // before the write: the answer can arrive before the write returns
                    c.write(CH_FUNCTION_LAUNCH_REQUEST, u16(LAUNCH_IN_CAMERA_VIEWING));
                    quietPolls = 0;
                    continue;
                }
            }
            if (quickRetries > 0 && !acceptedLogged && v == null && sinceRequest >= 900) {
                acceptedLogged = true;
                log.trace("richiesta accettata al tentativo " + (quickRetries + 1) + ": la camera rifiutava ancora "
                        + (lastRefusedAt - firstRequestAt) / 1_000_000L + " ms dopo la prima richiesta e ha accettato quella a "
                        + (requestAt - firstRequestAt) / 1_000_000L + " ms");
            }
            boolean fellBack = state == AP_NOT_LAUNCHED && sawLaunching;
            quietPolls = state == before ? quietPolls + 1 : 0;
            if (fellBack || (state == AP_NOT_LAUNCHED && quietPolls >= 3)) {
                int err = c.has(CH_STATE_ERROR_DETAILS) ? c.readU16(CH_STATE_ERROR_DETAILS) : 0;
                log.trace("dettagli errore camera = " + err);
                if (err == ERR_CAMERA_BUSY_SHORT && busyRetries++ < 5) {
                    g.sleep(200);
                    c.write(CH_FUNCTION_LAUNCH_REQUEST, u16(LAUNCH_IN_CAMERA_VIEWING));
                    sawLaunching = false;
                    quietPolls = 0;
                    continue;
                }
                if (fellBack || (err > 0 && err < 14)) throw new IOException(errorText(err));
                quietPolls = 0; // no reason given yet: keep waiting until the deadline
            }
        }
        String ssid = string(c.read(CH_SSID));
        if (ssid.isEmpty()) throw new IOException("La camera non ha comunicato il nome della rete Wi-Fi");
        String pass = "";
        if (c.has(CH_PASSPHRASE)) {
            try {
                pass = string(c.read(CH_PASSPHRASE));
            } catch (GattLink.GattException e) {
                log.log("Password Wi-Fi non leggibile (" + e.getStatus() + "), provo senza");
            }
        }
        String bssid = "";
        if (c.has(CH_MAC_ADDRESS)) {
            try {
                bssid = normalizeMac(string(c.read(CH_MAC_ADDRESS)));
            } catch (GattLink.GattException e) {
                // optional
            }
        }
        log.trace("Wi-Fi: SSID " + ssid + ", password " + (pass.isEmpty() ? "assente" : pass.length() + " caratteri")
                + ", BSSID " + (bssid.isEmpty() ? "-" : bssid));
        return new WifiCredentials(ssid, pass, bssid);
    }

    /**
     * What XApp does on the Bluetooth link between the app-info write and the Wi-Fi request (from the phone's
     * Bluetooth stack log of a working XApp import on an X-M5): subscribe to the camera's state characteristics,
     * set "image transfer setting ex" to 1, subscribe to the setting characteristics, send UTC time and time zone;
     * same order except the time, sent first (see below). Without these the camera starts its access point but
     * never answers on Wi-Fi. Every step is best effort: a missing characteristic is skipped.
     */
    static void prepareLikeXApp(GattLink g, boolean red, Log log) {
        String startup = red ? SVC_STARTUP_INFORMATION_RED : SVC_STARTUP_INFORMATION;
        // The time first, not last as in XApp's order: right after the link the X-M5 asks for date and position
        // (F9150137 / AD06C7B7 = 1) and answers the Wi-Fi request with "busy for a moment" for ~1.2-1.5 s; on the
        // connections where it did not ask, the request went through at once (log of 11-13 Nov). Written first,
        // the camera handles it during the subscriptions below.
        writeQuietly(g, SVC_CURRENT_TIME, CH_UTC_AND_TIMEZONE, utcAndTimeZone(System.currentTimeMillis(),
                java.util.TimeZone.getDefault()), "ora UTC e fuso", log);
        subscribe(g, SVC_CAMERA_STATE, CH_AP_STATE, "stato Wi-Fi", log);
        subscribe(g, SVC_CAMERA_STATE, CH_TRANSFER_STATE, "stato trasferimento", log);
        subscribe(g, SVC_CAMERA_STATE, CH_DATE_SYNC_STATE, "sincronizzazione data", log);
        subscribe(g, SVC_CAMERA_STATE, CH_LOCATION_SYNC_STATE, "sincronizzazione posizione", log);
        subscribe(g, startup, CH_REMOTE_BOOT_SETTING, "avvio remoto", log);
        readQuietly(g, SVC_CAMERA_STATE, CH_TRANSFER_STATE, "stato trasferimento", log);
        subscribe(g, SVC_CAMERA_SETTING, CH_SSID, "nome rete", log);
        subscribe(g, SVC_CAMERA_SETTING, CH_LOGGING_SETTING, "impostazione log", log);
        writeQuietly(g, SVC_CAMERA_SETTING, CH_IMAGE_TRANSFER_SETTING_EX, new byte[]{1}, "trasferimento immagini ex", log);
        subscribe(g, SVC_CAMERA_STATE, CH_CAMERA_VITAL_STATE, "stato camera", log);
        subscribe(g, SVC_CAMERA_SETTING, CH_LOCATION_SYNC_SETTING, "impostazione posizione", log);
        subscribe(g, SVC_CAMERA_SETTING, CH_IMAGE_RESIZE_SETTING, "ridimensionamento immagini", log);
        subscribe(g, SVC_CAMERA_SETTING, CH_LOCATION_SYNC_CYCLE, "intervallo posizione", log);
    }

    /** XApp's setChUTCAndTimeZone: UTC y(16) m d h min s, offset as hhmm-ish int32 (+1:30 = 150), DST flag. */
    static byte[] utcAndTimeZone(long nowMs, java.util.TimeZone tz) {
        java.util.Calendar u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"));
        u.setTimeInMillis(nowMs);
        int raw = tz.getRawOffset() / 1000;
        int hours = raw / 3600;
        int minutes = Math.abs(raw % 3600) / 60;
        int sign = hours < 0 ? -1 : 1;
        int offset = hours * 100 + (minutes == 15 ? sign * 25 : minutes == 30 ? sign * 50 : minutes == 45 ? sign * 75 : 0);
        byte[] b = new byte[12];
        Ptp.putU16(b, 0, u.get(java.util.Calendar.YEAR));
        b[2] = (byte) (u.get(java.util.Calendar.MONTH) + 1);
        b[3] = (byte) u.get(java.util.Calendar.DAY_OF_MONTH);
        b[4] = (byte) u.get(java.util.Calendar.HOUR_OF_DAY);
        b[5] = (byte) u.get(java.util.Calendar.MINUTE);
        b[6] = (byte) u.get(java.util.Calendar.SECOND);
        Ptp.putU32(b, 7, offset & 0xFFFFFFFFL);
        b[11] = (byte) (tz.inDaylightTime(new java.util.Date(nowMs)) ? 0 : 1); // as decompiled from XApp
        return b;
    }

    /**
     * XApp's setDisconnectedReason when the person stops: reason UserStop (u16 1), then "phone receiving images"
     * off. Best effort, before the Bluetooth link closes.
     */
    public static void notifyUserStop(GattLink g, boolean red, Log log) {
        writeQuietly(g, serviceOf(CH_DISCONNECTED_REASON, red), CH_DISCONNECTED_REASON, new byte[]{1, 0},
                "motivo disconnessione (utente)", log);
        writeQuietly(g, serviceOf(CH_IMAGE_RECEIVE_STATE, red), CH_IMAGE_RECEIVE_STATE, new byte[]{0},
                "ricezione immagini spenta", log);
    }

    private static void subscribe(GattLink g, String svc, String ch, String what, Log log) {
        if (!g.has(svc, ch)) {
            log.trace("assente: " + what);
            return;
        }
        try {
            g.enableNotifications(svc, ch);
            log.trace("notifiche attive: " + what);
        } catch (IOException e) {
            log.trace("notifiche " + what + " non attive: " + e.getMessage());
        }
    }

    private static void writeQuietly(GattLink g, String svc, String ch, byte[] v, String what, Log log) {
        if (!g.has(svc, ch)) {
            log.trace("assente: " + what);
            return;
        }
        try {
            log.trace("W " + what + " <- " + hex(v));
            g.write(svc, ch, v);
        } catch (IOException e) {
            log.trace("ignoro: " + what + ": " + e.getMessage());
        }
    }

    private static void readQuietly(GattLink g, String svc, String ch, String what, Log log) {
        if (!g.has(svc, ch)) return;
        try {
            log.trace("R " + what + " = " + hex(g.read(svc, ch)));
        } catch (IOException e) {
            log.trace("lettura " + what + " non riuscita: " + e.getMessage());
        }
    }

    public static String errorText(int err) {
        switch (err) {
            case 1: return "La camera sta usando la funzione FTP";
            case 2: return "La camera è occupata, riprova tra un attimo";
            case 3: return "La camera è occupata (registrazione o elaborazione in corso)";
            case 4: return "Termina l'operazione in corso sulla camera e riprova";
            case 5: return "Completa la configurazione iniziale della camera (data, lingua…)";
            case 6: return "La camera è in modalità USB: scollega il cavo";
            case 7:
            case 8: return "La camera è in modalità scatto remoto (tethering)";
            case 9: return "La camera è in modalità conversione RAW via USB";
            case 10: return "La camera è in modalità webcam";
            case 11: return "La camera è in modalità Live remoto";
            case 12: return "La camera ha immagini in attesa di trasferimento automatico";
            case 13: return "La camera sta usando Frame.io";
            default: return "La camera ha rifiutato di accendere il Wi-Fi";
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean launched(int state) {
        return state == AP_LAUNCHED || state == AP_LAUNCHED_RESERVED_TRANSFER;
    }

    private static String readSerial(Cam c) {
        if (!c.has(CH_SERIAL_NUMBER)) return "";
        try {
            return string(c.read(CH_SERIAL_NUMBER));
        } catch (IOException e) {
            c.log.trace("numero di serie non leggibile: " + e.getMessage());
            return "";
        }
    }

    private static void writeNameAndApp(Cam c, String phoneName) {
        c.tryWrite(CH_DEVICE_NAME, nameBytes(phoneName));
        c.tryWrite(CH_APP_INFO, new byte[]{(byte) 0x80, 0x01, 0x01}); // XApp, version 0x0101
    }

    static byte[] u16(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8)};
    }

    /** UTF-8 plus a terminating NUL, as XApp writes strings; capped so it always fits one packet. */
    static byte[] nameBytes(String s) {
        byte[] b = (s == null || s.isEmpty() ? "Android" : s).getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, 24);
        while (n > 0 && n < b.length && (b[n] & 0xC0) == 0x80) n--; // do not cut a character in half
        byte[] out = new byte[n + 1];
        System.arraycopy(b, 0, out, 0, n);
        return out;
    }

    /** Bytes up to the first NUL, UTF-8. */
    static String string(byte[] v) {
        int n = 0;
        while (n < v.length && v[n] != 0) n++;
        return new String(v, 0, n, StandardCharsets.UTF_8).trim();
    }

    static String normalizeMac(String s) {
        String m = s.trim().replace('-', ':').toUpperCase(Locale.ROOT);
        return m.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") ? m : "";
    }

    private static boolean containsUuid(List<String> uuids, String want) {
        if (uuids == null) return false;
        for (String u : uuids) if (want.equalsIgnoreCase(u)) return true;
        return false;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
