package com.fujixfer.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * Byte-stream view of a PTP transport. Both USB bulk and Fuji's TCP command pipe carry the same
 * container format (len32, type16, code16, transid32, payload), so everything above this
 * interface is transport independent.
 */
public interface PtpLink extends Closeable {
    void write(byte[] buf, int off, int len) throws IOException;

    /** Blocks until exactly len bytes are read, or throws (timeout, EOF, reset). */
    void readFully(byte[] buf, int off, int len) throws IOException;

    @Override
    void close();
}
