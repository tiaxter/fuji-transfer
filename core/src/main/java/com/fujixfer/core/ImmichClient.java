package com.fujixfer.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Immich client (pure Java): checks a file by its SHA-1 against the server and uploads it only when the
 * server does not have it yet. Uses {@code POST /api/assets/bulk-upload-check} (the same check Immich's own
 * CLI makes) and {@code POST /api/assets} (multipart). The server also refuses a duplicate on its own, so an
 * upload that races the check is harmless.
 */
public final class ImmichClient {
    /** A file to send: opened once for the hash and once for the upload. */
    public interface Source {
        InputStream open() throws IOException;

        long length() throws IOException;
    }

    public enum Outcome {
        UPLOADED, DUPLICATE
    }

    /** {@code permanent}: retrying will not help (wrong key, wrong address, file refused); otherwise try later. */
    public static final class ImmichException extends IOException {
        public final int code;
        public final boolean permanent;

        public ImmichException(String message, int code, boolean permanent) {
            super(message);
            this.code = code;
            this.permanent = permanent;
        }
    }

    private static final Pattern ACTION = Pattern.compile("\"action\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"(\\w+)\"");

    private final String api;
    private final String credential;
    private final boolean bearer;
    private final String deviceId;
    public int connectTimeoutMs = 10_000;
    public int readTimeoutMs = 300_000;

    /** {@code baseUrl}: "http://host:2283" or the same with "/api"; a missing scheme means http. */
    public ImmichClient(String baseUrl, String apiKey, String deviceId) {
        this(baseUrl, apiKey, deviceId, false);
    }

    /** {@code bearer}: {@code credential} is an access token from {@link #login}, not an API key. */
    public ImmichClient(String baseUrl, String credential, String deviceId, boolean bearer) {
        this.api = normalize(baseUrl);
        this.credential = credential.trim();
        this.bearer = bearer;
        this.deviceId = deviceId;
    }

    /**
     * Email and password, as in the web app: returns the session's access token (used with {@code bearer}).
     * The password is not kept.
     */
    public static String login(String baseUrl, String email, String password) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(normalize(baseUrl) + "/auth/login").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        byte[] req = ("{\"email\":\"" + json(email.trim()) + "\",\"password\":\"" + json(password) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(req.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(req);
        }
        String body;
        try {
            body = read(c, 200, 201);
        } catch (ImmichException e) {
            if (e.code == 400 || e.code == 401) throw new ImmichException("email o password sbagliate", e.code, true);
            throw e;
        }
        Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new ImmichException("risposta di Immich non riconosciuta", 200, false);
        return m.group(1);
    }

    /** Server version, "1.118.2" (GET /server/version, no login needed). */
    public String version() throws IOException {
        String body = read(open("/server/version", "GET"), 200);
        return num(body, "major") + "." + num(body, "minor") + "." + num(body, "patch");
    }

    /** Photos and videos of the account (GET /assets/statistics), or -1 if the server does not say. */
    public long assetCount() throws IOException {
        String body = read(open("/assets/statistics", "GET"), 200);
        String t = num(body, "total");
        return t.isEmpty() ? -1 : Long.parseLong(t);
    }

    private static String num(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    public static String normalize(String baseUrl) {
        String u = baseUrl.trim();
        if (!u.contains("://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.endsWith("/api")) u += "/api";
        return u;
    }

    /** Server reachable and key valid (GET /users/me). Returns the account's email, or "" if not told. */
    public String test() throws IOException {
        HttpURLConnection c = open("/users/me", "GET");
        String body = read(c, 200);
        Matcher m = Pattern.compile("\"email\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** True when the server already has a file with this SHA-1 (hex). */
    public boolean has(String sha1Hex, String id) throws IOException {
        HttpURLConnection c = open("/assets/bulk-upload-check", "POST");
        c.setRequestProperty("Content-Type", "application/json");
        byte[] req = ("{\"assets\":[{\"id\":\"" + json(id) + "\",\"checksum\":\"" + sha1Hex + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(req.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(req);
        }
        String body = read(c, 200);
        Matcher m = ACTION.matcher(body);
        if (!m.find()) throw new ImmichException("risposta di Immich non riconosciuta", 200, false);
        return m.group(1).equalsIgnoreCase("reject"); // reject = duplicate (or refused: the upload will say)
    }

    /**
     * Hashes the source, asks the server, and uploads only when the server does not have it.
     *
     * @param name       file name as the camera made it (DSCF1234.JPG)
     * @param createdMs  capture time, used when the file carries no date of its own
     * @param modifiedMs file modification time
     */
    public Outcome sync(String name, Source src, long createdMs, long modifiedMs) throws IOException {
        String sum;
        try (InputStream in = src.open()) {
            sum = sha1Hex(in);
        }
        String id = name + "-" + src.length() + "-" + modifiedMs;
        if (has(sum, id)) return Outcome.DUPLICATE;
        return upload(name, id, sum, src, createdMs, modifiedMs);
    }

    private Outcome upload(String name, String id, String sum, Source src, long createdMs, long modifiedMs)
            throws IOException {
        String boundary = "----fujixfer" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        field(head, boundary, "deviceAssetId", id);
        field(head, boundary, "deviceId", deviceId);
        field(head, boundary, "fileCreatedAt", iso(createdMs));
        field(head, boundary, "fileModifiedAt", iso(modifiedMs));
        field(head, boundary, "isFavorite", "false");
        head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"assetData\"; filename=\""
                + json(name) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long len = src.length();

        HttpURLConnection c = open("/assets", "POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("x-immich-checksum", sum); // newer servers refuse a duplicate before the body
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(head.size() + len + tail.length);
        long sent = 0;
        try (OutputStream o = c.getOutputStream(); InputStream in = src.open()) {
            head.writeTo(o);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                o.write(buf, 0, n);
                sent += n;
            }
            if (sent != len) throw new IOException("il file è cambiato durante l'invio");
            o.write(tail);
        }
        String body = read(c, 200, 201);
        Matcher m = STATUS.matcher(body);
        if (m.find() && m.group(1).equalsIgnoreCase("duplicate")) return Outcome.DUPLICATE;
        return Outcome.UPLOADED;
    }

    // ------------------------------------------------------------------ http

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(api + path).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + credential);
        else c.setRequestProperty("x-api-key", credential);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String read(HttpURLConnection c, int... ok) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            try (InputStream i = in) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = i.read(buf)) >= 0 && b.size() < 1 << 20) b.write(buf, 0, n);
                body = b.toString("UTF-8");
            }
        }
        c.disconnect();
        for (int k : ok) if (code == k) return body;
        if (code == 401 || code == 403) throw new ImmichException("accesso rifiutato (HTTP " + code + "): controlla chiave o password", code, true);
        if (code == 404) throw new ImmichException("indirizzo non valido: Immich non risponde qui (HTTP 404)", code, true);
        boolean transientError = code == 408 || code == 429 || code >= 500;
        throw new ImmichException("Immich ha risposto HTTP " + code, code, !transientError);
    }

    // ------------------------------------------------------------------ helpers

    public static String sha1Hex(InputStream in) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static void field(ByteArrayOutputStream o, String boundary, String name, String value) throws IOException {
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
