package com.fujixfer.core;

import java.io.IOException;

/** The camera answered, but with a non-OK PTP response code. */
public class PtpResponseException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int responseCode;

    public PtpResponseException(int opcode, int responseCode) {
        super(String.format("PTP op 0x%04X failed: %s", opcode, Ptp.rcName(responseCode)));
        this.responseCode = responseCode;
    }

    public int getResponseCode() {
        return responseCode;
    }
}
