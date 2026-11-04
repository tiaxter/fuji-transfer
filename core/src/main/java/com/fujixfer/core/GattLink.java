package com.fujixfer.core;

import java.io.IOException;

/**
 * The few GATT operations the Fuji Bluetooth protocol needs. Android implements it with BluetoothGatt;
 * the self test with a simulated camera. Calls are blocking and never run concurrently.
 * UUIDs are upper-case strings as in {@link FujiBle}.
 */
public interface GattLink {

    /** GATT error status reported by the phone (e.g. 5 = insufficient authentication). */
    final class GattException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public GattException(String what, int status) {
            super(what + " (GATT " + status + ")");
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        /** The camera wants an encrypted (bonded) link before answering (ATT 5, 8, 15 or Android's 137). */
        public boolean needsBonding() {
            return status == 5 || status == 8 || status == 15 || status == 137;
        }
    }

    /** True when the characteristic is reachable in that service (see {@link FujiBle#serviceOf}). */
    boolean has(String service, String characteristic);

    byte[] read(String service, String characteristic) throws IOException;

    /** Write with response. */
    void write(String service, String characteristic, byte[] value) throws IOException;

    /** Enables notifications or indications, whichever the characteristic supports. */
    void enableNotifications(String service, String characteristic) throws IOException;

    /** Next notification for the characteristic, or null after timeoutMs. */
    byte[] awaitNotification(String characteristic, long timeoutMs) throws IOException;

    /** Bonded (encrypted, remembered) at OS level. */
    boolean isBonded();

    /** Ask the phone to bond with the camera (Android shows its own dialog if needed). */
    void requestBond();

    void sleep(long ms) throws IOException;
}
