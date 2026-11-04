package com.fujixfer.core;

import java.io.IOException;

/**
 * Turns a packet oriented bulk pipe (USB bulk-in/bulk-out) into the byte stream {@link PtpLink}
 * expects. Handles short packets and zero-length packets, which Android's bulkTransfer returns as 0.
 */
public final class PacketLink implements PtpLink {
    public interface Bulk {
        /** Returns bytes read (0 for a zero-length packet). Throws on error or timeout. */
        int read(byte[] buf, int maxLen, int timeoutMs) throws IOException;

        void write(byte[] buf, int off, int len, int timeoutMs) throws IOException;

        void close();
    }

    private static final int MAX_CONSECUTIVE_EMPTY_READS = 8;

    private final Bulk bulk;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;
    private final byte[] pending;
    private int pendingPos = 0;
    private int pendingLen = 0;

    public PacketLink(Bulk bulk, int readBufferSize, int readTimeoutMs, int writeTimeoutMs) {
        this.bulk = bulk;
        this.pending = new byte[readBufferSize];
        this.readTimeoutMs = readTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        bulk.write(buf, off, len, writeTimeoutMs);
    }

    @Override
    public void readFully(byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            if (pendingPos == pendingLen) refill();
            int n = Math.min(len, pendingLen - pendingPos);
            System.arraycopy(pending, pendingPos, buf, off, n);
            pendingPos += n;
            off += n;
            len -= n;
        }
    }

    private void refill() throws IOException {
        for (int empty = 0; empty < MAX_CONSECUTIVE_EMPTY_READS; empty++) {
            int n = bulk.read(pending, pending.length, readTimeoutMs);
            if (n > 0) {
                pendingPos = 0;
                pendingLen = n;
                return;
            }
        }
        throw new IOException("Bulk-in returned only empty packets");
    }

    @Override
    public void close() {
        bulk.close();
    }
}
