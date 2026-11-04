package com.fujixfer.core;

/**
 * Minimal EXIF reader for the start of a JPEG (or the JPEG a RAF embeds): GPS position, Fujifilm film
 * simulation and orientation. Reads only what is there; anything malformed gives an empty result, never an exception.
 */
public final class Exif {
    private Exif() {
    }

    /** What the gallery uses. {@code lat}/{@code lon} are NaN when the file has no position. */
    public static final class Info {
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        /** Film simulation name ("Classic Chrome", "Acros +R"...) or null. */
        public String film;
        /**
         * EXIF Orientation (0x0112): 1 upright, 3 upside down, 6 to turn 90° clockwise, 8 to turn 90° counter-
         * clockwise (2, 4, 5, 7 mirrored). 0 when the file has no EXIF block or no such tag.
         */
        public int orientation;

        public boolean hasPosition() {
            return !Double.isNaN(lat) && !Double.isNaN(lon) && !(lat == 0 && lon == 0);
        }
    }

    /** RAF header: big-endian offset and length of the embedded JPEG at bytes 84 and 88. */
    public static long[] rafJpeg(byte[] head) {
        if (head.length < 92) return null;
        for (int i = 0; i < 15; i++) if (head[i] != "FUJIFILMCCD-RAW".charAt(i)) return null;
        long off = be32(head, 84);
        long len = be32(head, 88);
        if (off <= 0 || len <= 0) return null;
        return new long[]{off, len};
    }

    public static Info parse(byte[] d) {
        Info out = new Info();
        try {
            parseJpeg(d, out);
        } catch (RuntimeException e) {
            // truncated or odd file: keep what was read
        }
        return out;
    }

    private static void parseJpeg(byte[] d, Info out) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) return;
        int p = 2;
        while (p + 4 <= d.length) {
            if ((d[p] & 0xFF) != 0xFF) return;
            int marker = d[p + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return; // image data: no more metadata
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            int seg = p + 4;
            if (marker == 0xE1 && seg + 6 <= d.length && d[seg] == 'E' && d[seg + 1] == 'x' && d[seg + 2] == 'i'
                    && d[seg + 3] == 'f' && d[seg + 4] == 0 && d[seg + 5] == 0) {
                parseTiff(d, seg + 6, Math.min(d.length, p + 2 + len), out);
                return;
            }
            p += 2 + len;
        }
    }

    /** TIFF block at {@code base} (offsets in it are relative to base). */
    private static void parseTiff(byte[] d, int base, int end, Info out) {
        boolean le = d[base] == 'I';
        Reader r = new Reader(d, base, end, le);
        long ifd0 = r.u32(4);
        long gps = -1;
        long exif = -1;
        int n = r.u16(ifd0);
        for (int i = 0; i < n; i++) {
            long e = ifd0 + 2 + 12L * i;
            int tag = r.u16(e);
            if (tag == 0x8825) gps = r.u32(e + 8);
            if (tag == 0x0112) {
                int o = r.u16(e + 8); // SHORT, stored in the first bytes of the value field
                if (o >= 1 && o <= 8) out.orientation = o;
            }
            if (tag == 0x8769) exif = r.u32(e + 8);
        }
        if (gps > 0) readGps(r, gps, out);
        if (exif > 0) {
            int m = r.u16(exif);
            for (int i = 0; i < m; i++) {
                long e = exif + 2 + 12L * i;
                if (r.u16(e) == 0x927C) { // MakerNote
                    long count = r.u32(e + 4);
                    long off = r.u32(e + 8);
                    readFujiMakerNote(d, base + (int) off, Math.min(end, base + (int) (off + count)), out);
                }
            }
        }
    }

    private static void readGps(Reader r, long ifd, Info out) {
        int n = r.u16(ifd);
        char latRef = 0;
        char lonRef = 0;
        double lat = Double.NaN;
        double lon = Double.NaN;
        for (int i = 0; i < n; i++) {
            long e = ifd + 2 + 12L * i;
            int tag = r.u16(e);
            switch (tag) {
                case 1:
                    latRef = (char) r.u8(e + 8);
                    break;
                case 2:
                    lat = dms(r, r.u32(e + 8));
                    break;
                case 3:
                    lonRef = (char) r.u8(e + 8);
                    break;
                case 4:
                    lon = dms(r, r.u32(e + 8));
                    break;
                default:
                    break;
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        out.lat = latRef == 'S' ? -lat : lat;
        out.lon = lonRef == 'W' ? -lon : lon;
    }

    private static double dms(Reader r, long off) {
        double deg = rational(r, off);
        double min = rational(r, off + 8);
        double sec = rational(r, off + 16);
        return deg + min / 60.0 + sec / 3600.0;
    }

    private static double rational(Reader r, long off) {
        long num = r.u32(off);
        long den = r.u32(off + 4);
        return den == 0 ? 0 : (double) num / den;
    }

    /**
     * Fujifilm maker note: "FUJIFILM", then a little-endian u32 offset of its IFD, offsets relative to the
     * maker note itself. 0x1401 FilmMode; 0x1003 Saturation also carries the monochrome / Acros modes.
     */
    private static void readFujiMakerNote(byte[] d, int start, int end, Info out) {
        if (start + 12 > end) return;
        for (int i = 0; i < 8; i++) if (d[start + i] != "FUJIFILM".charAt(i)) return;
        Reader r = new Reader(d, start, end, true);
        long ifd = r.u32(8);
        int n = r.u16(ifd);
        int filmMode = -1;
        int saturation = -1;
        for (int i = 0; i < n; i++) {
            long e = ifd + 2 + 12L * i;
            int tag = r.u16(e);
            if (tag == 0x1401) filmMode = r.u16(e + 8);
            if (tag == 0x1003) saturation = r.u16(e + 8);
        }
        String mono = monochrome(saturation);
        out.film = mono != null ? mono : filmName(filmMode);
    }

    static String monochrome(int v) {
        switch (v) {
            case 0x300: return "Monocromo";
            case 0x301: return "Monocromo +R";
            case 0x302: return "Monocromo +Ye";
            case 0x303: return "Monocromo +G";
            case 0x310: return "Seppia";
            case 0x500: return "Acros";
            case 0x501: return "Acros +R";
            case 0x502: return "Acros +Ye";
            case 0x503: return "Acros +G";
            default: return null;
        }
    }

    static String filmName(int v) {
        switch (v) {
            case 0x000: return "Provia";
            case 0x100: case 0x110: case 0x130: case 0x300: return "Studio Portrait";
            case 0x120: return "Astia";
            case 0x200: case 0x400: return "Velvia";
            case 0x500: return "Pro Neg. Std";
            case 0x501: return "Pro Neg. Hi";
            case 0x600: return "Classic Chrome";
            case 0x700: return "Eterna";
            case 0x800: return "Classic Neg.";
            case 0x900: return "Eterna Bleach Bypass";
            case 0xA00: return "Nostalgic Neg.";
            case 0xB00: return "Reala Ace";
            default: return null;
        }
    }

    private static long be32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** Bounds-checked reads relative to a base offset, in the block's byte order. */
    private static final class Reader {
        final byte[] d;
        final int base;
        final int end;
        final boolean le;

        Reader(byte[] d, int base, int end, boolean le) {
            this.d = d;
            this.base = base;
            this.end = end;
            this.le = le;
        }

        int at(long off, int size) {
            long p = base + off;
            if (off < 0 || p + size > end) throw new IndexOutOfBoundsException();
            return (int) p;
        }

        int u8(long off) {
            return d[at(off, 1)] & 0xFF;
        }

        int u16(long off) {
            int p = at(off, 2);
            return le ? (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) : ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
        }

        long u32(long off) {
            int p = at(off, 4);
            return le
                    ? (d[p] & 0xFFL) | ((d[p + 1] & 0xFFL) << 8) | ((d[p + 2] & 0xFFL) << 16) | ((d[p + 3] & 0xFFL) << 24)
                    : ((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFFL) << 16) | ((d[p + 2] & 0xFFL) << 8) | (d[p + 3] & 0xFFL);
        }
    }
}
