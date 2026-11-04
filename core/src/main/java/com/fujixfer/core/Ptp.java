package com.fujixfer.core;

/** PTP constants and little-endian helpers shared by the USB and Fuji Wi-Fi transports. */
public final class Ptp {
    private Ptp() {}

    // Container types (identical on USB bulk and on Fuji's TCP command pipe)
    public static final int TYPE_COMMAND = 1;
    public static final int TYPE_DATA = 2;
    public static final int TYPE_RESPONSE = 3;
    public static final int TYPE_EVENT = 4;
    public static final int HEADER_SIZE = 12;

    // Operations
    public static final int OC_GET_DEVICE_INFO = 0x1001;
    public static final int OC_OPEN_SESSION = 0x1002;
    public static final int OC_CLOSE_SESSION = 0x1003;
    public static final int OC_GET_STORAGE_IDS = 0x1004;
    public static final int OC_GET_OBJECT_HANDLES = 0x1007;
    public static final int OC_GET_OBJECT_INFO = 0x1008;
    public static final int OC_GET_OBJECT = 0x1009;
    public static final int OC_GET_THUMB = 0x100A;
    public static final int OC_GET_DEVICE_PROP_VALUE = 0x1015;
    public static final int OC_SET_DEVICE_PROP_VALUE = 0x1016;
    public static final int OC_TERMINATE_OPEN_CAPTURE = 0x1018;
    public static final int OC_INITIATE_OPEN_CAPTURE = 0x101C;
    public static final int OC_GET_PARTIAL_OBJECT = 0x101B;
    public static final int OC_GET_OBJECT_PROP_VALUE = 0x9803;

    // Fuji specific (see libgphoto2 ptp.h)
    public static final int OC_FUJI_GET_DEVICE_INFO = 0x902B;
    /** XApp gallery set-up calls (see FujiWifi): import folders, import dates, extension object info. */
    public static final int OC_FUJI_GET_IMPORT_FOLDERS = 0x9050;
    public static final int OC_FUJI_GET_IMPORT_DATES = 0x9053;
    public static final int OC_FUJI_GET_EXT_OBJECT_INFO = 0x9054;
    /** Fuji extension thumbnail; same call shape as GetThumb: {handle}. */
    public static final int OC_FUJI_GET_EXT_THUMB = 0x9055;
    /** Found in the original app's libFTLPTPIP.so: params {handle, offsetLow, length, offsetHigh}. */
    public static final int OC_FUJI_GET_EXT_PARTIAL_OBJECT = 0x9056;

    // Responses
    public static final int RC_OK = 0x2001;
    public static final int RC_GENERAL_ERROR = 0x2002;
    public static final int RC_SESSION_NOT_OPEN = 0x2003;
    public static final int RC_INVALID_TRANSACTION_ID = 0x2004;
    public static final int RC_OPERATION_NOT_SUPPORTED = 0x2005;
    public static final int RC_PARAMETER_NOT_SUPPORTED = 0x2006;
    public static final int RC_INCOMPLETE_TRANSFER = 0x2007;
    public static final int RC_INVALID_STORAGE_ID = 0x2008;
    public static final int RC_INVALID_OBJECT_HANDLE = 0x2009;
    public static final int RC_NO_THUMBNAIL_PRESENT = 0x2010;
    public static final int RC_DEVICE_BUSY = 0x2019;
    public static final int RC_SESSION_ALREADY_OPEN = 0x201E;
    public static final int RC_TRANSACTION_CANCELLED = 0x201F;

    // Object property
    public static final int OPC_OBJECT_SIZE = 0xDC04;

    // Object formats
    public static final int OFC_ASSOCIATION = 0x3001;

    public static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    public static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    public static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    public static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    public static String rcName(int rc) {
        switch (rc) {
            case RC_OK: return "OK";
            case RC_GENERAL_ERROR: return "GeneralError";
            case RC_SESSION_NOT_OPEN: return "SessionNotOpen";
            case RC_INVALID_TRANSACTION_ID: return "InvalidTransactionID";
            case RC_OPERATION_NOT_SUPPORTED: return "OperationNotSupported";
            case RC_PARAMETER_NOT_SUPPORTED: return "ParameterNotSupported";
            case RC_INCOMPLETE_TRANSFER: return "IncompleteTransfer";
            case RC_INVALID_STORAGE_ID: return "InvalidStorageID";
            case RC_INVALID_OBJECT_HANDLE: return "InvalidObjectHandle";
            case RC_NO_THUMBNAIL_PRESENT: return "NoThumbnailPresent";
            case RC_DEVICE_BUSY: return "DeviceBusy";
            case RC_SESSION_ALREADY_OPEN: return "SessionAlreadyOpen";
            case RC_TRANSACTION_CANCELLED: return "TransactionCancelled";
            default: return String.format("0x%04X", rc);
        }
    }
}
