package com.fujixfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simulated Fuji body on the GATT side, behaving as XApp's code expects, with fault injection.
 * Characteristics live in services, like on the real body: a RED body may still expose the legacy
 * "connected device" service, which rejects writes (status 133) — the bug seen on a real phone.
 */
class FakeBleCamera implements GattLink {
    static final class Write {
        final String svc;
        final String ch;
        final byte[] value;

        Write(String svc, String ch, byte[] value) {
            this.svc = svc;
            this.ch = ch;
            this.value = value;
        }
    }

    final boolean red;
    /** "service|characteristic" */
    final Set<String> chars = new HashSet<>();
    final Map<String, byte[]> values = new HashMap<>();
    final List<Write> writes = new ArrayList<>();
    final List<String> subscribed = new ArrayList<>();

    /** The extra characteristics an X-M5 exposes and XApp touches before the Wi-Fi request. */
    void addXAppExtras() {
        for (String ch : new String[]{FujiBle.CH_TRANSFER_STATE, FujiBle.CH_DATE_SYNC_STATE, FujiBle.CH_LOCATION_SYNC_STATE,
                FujiBle.CH_CAMERA_VITAL_STATE}) add(FujiBle.SVC_CAMERA_STATE, ch);
        for (String ch : new String[]{FujiBle.CH_LOGGING_SETTING, FujiBle.CH_IMAGE_TRANSFER_SETTING_EX,
                FujiBle.CH_LOCATION_SYNC_SETTING, FujiBle.CH_IMAGE_RESIZE_SETTING, FujiBle.CH_LOCATION_SYNC_CYCLE}) {
            add(FujiBle.SVC_CAMERA_SETTING, ch);
        }
        add(FujiBle.SVC_STARTUP_INFORMATION_RED, FujiBle.CH_REMOTE_BOOT_SETTING);
        add(FujiBle.SVC_CURRENT_TIME, FujiBle.CH_UTC_AND_TIMEZONE);
        values.put(FujiBle.CH_TRANSFER_STATE, new byte[]{0});
    }
    /** Like the X-M5: on the subscription it notifies DATE_SYNC_STATE = 1 (asks for the time). */
    boolean asksDateOnSubscribe;
    private final ArrayDeque<byte[]> dateNotifications = new ArrayDeque<>();
    private final ArrayDeque<byte[]> apNotifications = new ArrayDeque<>();
    private boolean apNotifyEnabled;

    // legacy
    byte[] expectedKey;
    // RED
    boolean bonded;
    /** Writes/reads in the RED service fail until bonded; with this status (5 = ATT, 133 = some phones). */
    int unbondedStatus = 5;
    int zeroReadsBeforeConfirm = 0;
    long cameraNumber = 0x00001234L;
    /** Also expose the legacy service on a RED body; writes there answer 133. */
    boolean legacyDecoy = false;
    // faults
    int transient133Writes = 0;
    boolean notificationsFail = false;
    // Wi-Fi launch
    int busyAnswers = 0;
    /** Like the X-M5: a refused request comes back as one "not launched" notification, no "launching" first. */
    boolean quietRefusal = false;
    /** What the phone did on the Wi-Fi launch, in order: "W" request, "S" state read, "E" error details read. */
    final List<String> launchOps = new ArrayList<>();
    int refuseWith = 0;
    boolean deliverNotifications = true;
    int launchRequests = 0;
    int bondRequests = 0;
    long sleptMs = 0;

    FakeBleCamera(boolean red) {
        this.red = red;
        add(FujiBle.SVC_DEVICE_INFORMATION, FujiBle.CH_SERIAL_NUMBER);
        add(FujiBle.SVC_CAMERA_CONTROL, FujiBle.CH_FUNCTION_LAUNCH_REQUEST);
        add(FujiBle.SVC_CAMERA_STATE, FujiBle.CH_AP_STATE);
        add(FujiBle.SVC_CAMERA_STATE, FujiBle.CH_STATE_ERROR_DETAILS);
        add(FujiBle.SVC_CAMERA_SETTING, FujiBle.CH_SSID);
        add(FujiBle.SVC_CAMERA_SETTING, FujiBle.CH_PASSPHRASE);
        String dev = red ? FujiBle.SVC_CONNECTED_DEVICE_INFORMATION_RED : FujiBle.SVC_CONNECTED_DEVICE_INFORMATION;
        add(dev, FujiBle.CH_DEVICE_NAME);
        add(dev, FujiBle.CH_APP_INFO);
        add(dev, FujiBle.CH_PAIRING_KEY);
        if (red) add(dev, FujiBle.CH_IDENTIFICATION_NUMBER);
        add(red ? FujiBle.SVC_CAMERA_INFORMATION_RED : FujiBle.SVC_CAMERA_INFORMATION, FujiBle.CH_MAC_ADDRESS);
        values.put(FujiBle.CH_SERIAL_NUMBER, z("12345678"));
        values.put(FujiBle.CH_SSID, z("FUJIFILM-X-T5-ABCD"));
        values.put(FujiBle.CH_PASSPHRASE, z("secret123"));
        values.put(FujiBle.CH_MAC_ADDRESS, z("aa-bb-cc-dd-ee-ff"));
        values.put(FujiBle.CH_AP_STATE, FujiBle.u16(FujiBle.AP_NOT_LAUNCHED));
        values.put(FujiBle.CH_STATE_ERROR_DETAILS, FujiBle.u16(0));
    }

    FakeBleCamera withLegacyDecoy() {
        legacyDecoy = true;
        add(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION, FujiBle.CH_DEVICE_NAME);
        add(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION, FujiBle.CH_APP_INFO);
        add(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION, FujiBle.CH_PAIRING_KEY);
        add(FujiBle.SVC_CAMERA_INFORMATION, FujiBle.CH_MAC_ADDRESS);
        return this;
    }

    void add(String svc, String ch) {
        chars.add(svc + "|" + ch);
    }

    static byte[] z(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(b, b.length + 1);
    }

    List<Write> writesTo(String ch) {
        List<Write> out = new ArrayList<>();
        for (Write w : writes) if (w.ch.equals(ch)) out.add(w);
        return out;
    }

    List<Write> writesToService(String svc) {
        List<Write> out = new ArrayList<>();
        for (Write w : writes) if (w.svc.equals(svc)) out.add(w);
        return out;
    }

    private boolean protectedService(String svc) {
        return red && svc.equals(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION_RED);
    }

    @Override
    public boolean has(String svc, String c) {
        return chars.contains(svc + "|" + c);
    }

    @Override
    public byte[] read(String svc, String c) throws IOException {
        if (!has(svc, c)) throw new GattException("no such characteristic", 0x0A);
        if (protectedService(svc) && !bonded) throw new GattException("read", unbondedStatus);
        if (c.equals(FujiBle.CH_IDENTIFICATION_NUMBER)) {
            byte[] v = new byte[4];
            if (zeroReadsBeforeConfirm > 0) {
                zeroReadsBeforeConfirm--;
            } else {
                Ptp.putU32(v, 0, cameraNumber);
            }
            return v;
        }
        if (c.equals(FujiBle.CH_AP_STATE)) launchOps.add("S");
        if (c.equals(FujiBle.CH_STATE_ERROR_DETAILS)) launchOps.add("E");
        return values.getOrDefault(c, new byte[0]).clone();
    }

    @Override
    public void write(String svc, String c, byte[] value) throws IOException {
        if (!has(svc, c)) throw new GattException("no such characteristic", 0x0A);
        writes.add(new Write(svc, c, value.clone()));
        if (legacyDecoy && svc.equals(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION)) throw new GattException("write", 133);
        if (protectedService(svc) && !bonded) throw new GattException("write", unbondedStatus);
        if (transient133Writes > 0) {
            transient133Writes--;
            throw new GattException("write", 133);
        }
        if (c.equals(FujiBle.CH_PAIRING_KEY) && expectedKey != null && !Arrays.equals(value, expectedKey)) {
            throw new GattException("wrong pairing key", 0x80);
        }
        if (c.equals(FujiBle.CH_FUNCTION_LAUNCH_REQUEST) && value.length == 2 && Ptp.u16(value, 0) == 3) {
            launchRequests++;
            launchOps.add("W");
            if (!(quietRefusal && busyAnswers > 0)) setAp(FujiBle.AP_LAUNCHING);
            if (busyAnswers > 0) {
                busyAnswers--;
                values.put(FujiBle.CH_STATE_ERROR_DETAILS, FujiBle.u16(FujiBle.ERR_CAMERA_BUSY_SHORT));
                setAp(FujiBle.AP_NOT_LAUNCHED);
            } else if (refuseWith != 0) {
                values.put(FujiBle.CH_STATE_ERROR_DETAILS, FujiBle.u16(refuseWith));
                setAp(FujiBle.AP_NOT_LAUNCHED);
            } else {
                values.put(FujiBle.CH_STATE_ERROR_DETAILS, FujiBle.u16(0));
                setAp(FujiBle.AP_LAUNCHED);
            }
        }
    }

    private void setAp(int state) {
        values.put(FujiBle.CH_AP_STATE, FujiBle.u16(state));
        if (apNotifyEnabled && deliverNotifications) apNotifications.add(FujiBle.u16(state));
    }

    @Override
    public void enableNotifications(String svc, String c) throws IOException {
        if (notificationsFail) throw new GattException("cccd", 133);
        subscribed.add(c);
        if (c.equals(FujiBle.CH_AP_STATE)) apNotifyEnabled = true;
        if (c.equals(FujiBle.CH_DATE_SYNC_STATE) && asksDateOnSubscribe) dateNotifications.add(new byte[]{1, 0});
    }

    @Override
    public byte[] awaitNotification(String c, long timeoutMs) {
        if (c.equals(FujiBle.CH_DATE_SYNC_STATE)) return dateNotifications.poll();
        if (!c.equals(FujiBle.CH_AP_STATE)) return null;
        byte[] v = apNotifications.poll();
        if (v == null) {
            try {
                Thread.sleep(Math.min(timeoutMs, 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return v;
    }

    @Override
    public boolean isBonded() {
        return bonded;
    }

    @Override
    public void requestBond() {
        bondRequests++;
        bonded = true;
    }

    @Override
    public void sleep(long ms) {
        sleptMs += ms;
    }
}
