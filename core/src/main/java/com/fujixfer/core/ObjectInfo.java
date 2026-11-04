package com.fujixfer.core;

/** Subset of the PTP ObjectInfo dataset that the downloader needs. */
public final class ObjectInfo {
    public final long handle;
    public final long storageId;
    public final int format;
    /** Size in bytes. 0xFFFFFFFF means "4 GiB or more" (reported by PTP when it overflows 32 bits). */
    public final long size;
    public final String filename;
    /** Raw PTP date string, e.g. 20261030T194500 (may be empty). */
    public final String captureDate;
    public final long width;
    public final long height;

    public ObjectInfo(long handle, long storageId, int format, long size, String filename,
                      String captureDate, long width, long height) {
        this.handle = handle;
        this.storageId = storageId;
        this.format = format;
        this.size = size;
        this.filename = filename;
        this.captureDate = captureDate;
        this.width = width;
        this.height = height;
    }

    public ObjectInfo withHandle(long newHandle) {
        return new ObjectInfo(newHandle, storageId, format, size, filename, captureDate, width, height);
    }

    public ObjectInfo withSize(long newSize) {
        return new ObjectInfo(handle, storageId, format, newSize, filename, captureDate, width, height);
    }

    /** Same file as seen by the camera (size 0xFFFFFFFF in PTP means "4 GiB or more"). */
    public boolean sameFileAs(ObjectInfo other) {
        if (!filename.equals(other.filename) || !captureDate.equals(other.captureDate)) return false;
        return size == other.size || (sizeOverflow() && other.size >= 0xFFFFFFFFL)
                || (other.sizeOverflow() && size >= 0xFFFFFFFFL);
    }

    public boolean isFolder() {
        return format == Ptp.OFC_ASSOCIATION;
    }

    public boolean sizeOverflow() {
        return size == 0xFFFFFFFFL;
    }

    /** One tab-separated line, for caching a listing between connections. */
    public String toLine() {
        return handle + "\t" + storageId + "\t" + format + "\t" + size + "\t" + clean(filename) + "\t"
                + clean(captureDate) + "\t" + width + "\t" + height;
    }

    /** Inverse of {@link #toLine}; null for a malformed line. */
    public static ObjectInfo fromLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 8) return null;
        try {
            return new ObjectInfo(Long.parseLong(f[0]), Long.parseLong(f[1]), Integer.parseInt(f[2]),
                    Long.parseLong(f[3]), f[4], f[5], Long.parseLong(f[6]), Long.parseLong(f[7]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    @Override
    public String toString() {
        return filename + " (" + size + " B, handle " + handle + ")";
    }
}
