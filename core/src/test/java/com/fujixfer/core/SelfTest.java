package com.fujixfer.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;

/** Plain-Java self test (no JUnit needed): run with `gradle :core:selfTest` or java -cp ... SelfTest. */
public final class SelfTest {
    private static final byte[] GUID = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        basicDownload();
        pairingWait();
        cameraStartsLateThenAnswersSlowly();
        silentFirstSocketLikeXApp();
        dropsAndResume();
        handlesChangeAfterReconnect();
        stalledConnection();
        noPartialObjectSupport();
        resumeFromExistingPartFile();
        largeFileCrossing4GiB();
        packetLinkReassembly();
        thumbnails();
        thumbnailsAfterReconnect();
        xappSessionSetup();
        fastListing();
        listingWithoutSlowMode();
        pipelinedListing();
        queueDroppingCamera();
        cacheCheckAvoidsRaf();
        shortPartialReplies();
        downloadIntoChannel();
        viewerPreviews();
        dateGroupsLayout();
        exifReading();
        listingPriority();
        thumbnailsDuringListing();
        genericPtpIsIgnored();
        slowClientStateAnswer();
        classicWifiFromCameraMenu();
        olderBodyAfterBluetooth();
        bleLegacyPairing();
        bleRedPairing();
        bleWifiLaunch();
        bleXAppPreLaunchSequence();
        bleAdoptXAppPairing();
        metaProbe();
        immichSync();
        if (failures > 0) {
            System.out.println("\n" + failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.out.println("\nALL CHECKS PASSED");
    }

    // ------------------------------------------------------------------ scenarios

    static void basicDownload() throws Exception {
        System.out.println("== basic list + download, empty DeviceInfo like X-T4");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] jpg = FakeFujiCamera.pattern(3 * 1024 * 1024 + 123, 1);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, jpg, jpg.length));
            TransferEngine eng = engine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            check("one object listed", list.size() == 1);
            check("filename parsed", "DSCF0001.JPG".equals(list.get(0).filename));
            check("size parsed (real size asked when needed)", eng.withRealSize(list.get(0)).size == jpg.length);
            check("dimensions parsed", list.get(0).width == 6000 && list.get(0).height == 4000);
            File out = eng.download(list.get(0), tmp(), null);
            check("sha256 matches", sha(out).equals(sha(jpg)));
            check("init packet always 82 bytes", cam.badInitPackets.get() == 0);
            check("client id: first 12 bytes as given", Arrays.equals(Arrays.copyOf(cam.lastGuid, 12), Arrays.copyOf(GUID, 12)));
            check("phone IP at bytes 24..27 like XApp (127.0.0.1 -> 01 00 00 7f)",
                    bytesToHex(Arrays.copyOfRange(cam.lastGuid, 12, 16)).equals("0100007f"));
            byte[] x = FujiTcpLink.buildInitRequest(FujiTcpLink.FUJI_CLIENT_GUID, "PME110",
                    java.net.InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, (byte) 129}));
            check("init for 192.168.0.129 matches XApp's layout", bytesToHex(Arrays.copyOfRange(x, 12, 40))
                    .equals("ada5485d87b27f0bd3d5ded08100a8c0" + "50004d0045003100310030"  + "00"));
            check("fixed Fuji client GUID matches the APK template",
                    bytesToHex(FujiTcpLink.FUJI_CLIENT_GUID).equals("ada5485d87b27f0bd3d5ded000000000"));
            eng.close();
        }
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void pairingWait() throws Exception {
        System.out.println("== camera answers INIT_FAIL three times before accepting (user taps OK on camera)");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.initFailuresRemaining = 3;
            TransferEngine eng = engine(cam);
            check("session opens", eng.session().isOpen());
            check("three INIT_FAIL were absorbed on one socket", cam.initFailsSent.get() == 3 && cam.connections.get() == 1);
            eng.close();
        }
    }

    static void cameraStartsLateThenAnswersSlowly() throws Exception {
        System.out.println("== X-M5 log: service not up yet (refused), then the camera answers the handshake slowly");
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        final int p = port;
        final FakeFujiCamera[] holder = new FakeFujiCamera[1];
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(2500); // access point up, transfer service not yet
                FakeFujiCamera cam = new FakeFujiCamera(p);
                cam.ackDelayMs = 4000; // camera takes a few seconds to answer the handshake
                cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, new byte[1000], 1000));
                holder[0] = cam;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        starter.start();
        FujiTcpLink.Options lo = new FujiTcpLink.Options();
        lo.readTimeoutMs = 1500; // shorter than the camera's silence: the old code gave up here
        lo.connectTimeoutMs = 1000;
        lo.serviceWaitMs = 10000;
        lo.handshakeTimeoutMs = 10000;
        AtomicInteger told = new AtomicInteger();
        lo.onWaitingForCamera = told::incrementAndGet;
        long t0 = System.currentTimeMillis();
        PtpSession s = FujiTcpLink.openSession(SocketFactory.getDefault(), "127.0.0.1", p, GUID, "phone", lo);
        check("connected after refusals and a 4 s wait (" + (System.currentTimeMillis() - t0) + " ms)", s.isOpen());
        check("one socket only: the wait did not restart the session", holder[0].connections.get() == 1);
        check("progress note shown once while waiting", told.get() == 1);
        s.close();
        starter.join();
        holder[0].close();
    }

    static void silentFirstSocketLikeXApp() throws Exception {
        System.out.println("== camera ignores the first two sockets: new socket every 5 s, like XApp's open loop");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.silentSockets = 2;
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, new byte[1000], 1000));
            FujiTcpLink.Options lo = linkOptions();
            lo.handshakeTimeoutMs = 2500; // note after one 1.5 s read step
            AtomicInteger told = new AtomicInteger();
            lo.onWaitingForCamera = told::incrementAndGet;
            PtpSession s = FujiTcpLink.openSession(SocketFactory.getDefault(), "127.0.0.1", cam.port(), GUID, "phone", lo,
                    new FujiWifi.Options(), new Notes());
            check("connected on the third socket", s.isOpen() && cam.connections.get() == 3);
            check("waiting note shown once, not per socket", told.get() == 1);
            s.close();
        }
    }

    static void dropsAndResume() throws Exception {
        System.out.println("== 24 MB RAF, connection reset twice mid-transfer");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] raf = FakeFujiCamera.pattern(24 * 1024 * 1024, 2);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0002.RAF", 0xB103, raf, raf.length));
            cam.dropsRemaining = 2;
            cam.faultAfterDataBytes = 5 * 1024 * 1024;
            TransferEngine eng = engine(cam);
            AtomicInteger retries = new AtomicInteger();
            ObjectInfo info = eng.listObjects(null).get(0);
            File out = eng.download(info, tmp(), listener(retries));
            check("sha256 matches", sha(out).equals(sha(raf)));
            check("two retries happened", retries.get() == 2);
            long wasted = cam.dataBytesSent.get() - raf.length;
            check("resume: re-sent < 1 chunk per drop (wasted=" + wasted + ")", wasted <= 2L * 2 * 1024 * 1024);
            TransferEngine.Stats st = eng.lastStats();
            check("stats: bytes " + st.bytes + ", " + st.chunks + " pieces, " + st.retries + " retries",
                    st.bytes == raf.length && st.chunks >= raf.length / FujiWifi.MAX_PARTIAL && st.retries == 2
                            && st.nanos > 0 && st.waitNanos > 0 && st.writeNanos > 0 && st.waitNanos < st.nanos);
            eng.close();
        }
    }

    static void handlesChangeAfterReconnect() throws Exception {
        System.out.println("== object handles are renumbered after reconnect");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] a = FakeFujiCamera.pattern(2 * 1024 * 1024, 3);
            byte[] b = FakeFujiCamera.pattern(9 * 1024 * 1024, 4);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0003.JPG", 0x3801, a, a.length));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0004.RAF", 0xB103, b, b.length));
            cam.shuffleHandlesPerConnection = true;
            cam.dropsRemaining = 1;
            cam.faultAfterDataBytes = 3 * 1024 * 1024;
            TransferEngine eng = engine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            ObjectInfo raf = list.get(1);
            File out = eng.download(raf, tmp(), listener(new AtomicInteger()));
            check("sha256 matches after handle change", sha(out).equals(sha(b)));
            eng.close();
        }
    }

    static void stalledConnection() throws Exception {
        System.out.println("== camera stops sending but keeps the socket open (silent Wi-Fi stall)");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] d = FakeFujiCamera.pattern(8 * 1024 * 1024, 5);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0005.RAF", 0xB103, d, d.length));
            cam.stallsRemaining = 1;
            cam.faultAfterDataBytes = 2 * 1024 * 1024;
            TransferEngine eng = engine(cam);
            AtomicInteger retries = new AtomicInteger();
            long t0 = System.currentTimeMillis();
            File out = eng.download(eng.listObjects(null).get(0), tmp(), listener(retries));
            check("sha256 matches", sha(out).equals(sha(d)));
            check("stall detected by read timeout, not hung (" + (System.currentTimeMillis() - t0) + " ms)",
                    retries.get() == 1 && System.currentTimeMillis() - t0 < 7000);
            eng.close();
        }
    }

    static void noPartialObjectSupport() throws Exception {
        System.out.println("== body without GetPartialObject: falls back to whole-object download");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.partialSupported = false;
            cam.emptyDeviceInfo = false; // advertise ops, but this variant also rejects at runtime
            byte[] d = FakeFujiCamera.pattern(4 * 1024 * 1024, 6);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0006.JPG", 0x3801, d, d.length));
            cam.dropsRemaining = 1;
            cam.faultAfterDataBytes = 1024 * 1024;
            TransferEngine eng = engine(cam);
            File out = eng.download(eng.listObjects(null).get(0), tmp(), listener(new AtomicInteger()));
            check("sha256 matches", sha(out).equals(sha(d)));
            eng.close();
        }
    }

    static void resumeFromExistingPartFile() throws Exception {
        System.out.println("== app was killed earlier: existing .part file is reused");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] d = FakeFujiCamera.pattern(10 * 1024 * 1024, 7);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0007.RAF", 0xB103, d, d.length));
            TransferEngine eng = engine(cam);
            ObjectInfo info = eng.withRealSize(eng.listObjects(null).get(0)); // .part files carry the real size
            File dir = tmp();
            File part = new File(dir, info.filename + "." + info.size + "." + info.captureDate.replaceAll("[^0-9A-Za-z]", "") + ".part");
            try (RandomAccessFile r = new RandomAccessFile(part, "rw")) {
                r.write(d, 0, 6 * 1024 * 1024);
            }
            cam.dataBytesSent.set(0);
            File out = eng.download(info, dir, null);
            check("sha256 matches", sha(out).equals(sha(d)));
            check("only the missing 4 MB were transferred (" + cam.dataBytesSent.get() + ")",
                    cam.dataBytesSent.get() == 4L * 1024 * 1024);
            eng.close();
        }
    }

    static void largeFileCrossing4GiB() throws Exception {
        System.out.println("== video of 4 GiB + 3 MiB: PTP reports 0xFFFFFFFF, real size via ObjectSize, 64-bit offsets via 0x9056");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            long total = (1L << 32) + 3L * 1024 * 1024;
            FakeFujiCamera.Obj big = new FakeFujiCamera.Obj("DSCF0008.MOV", 0x300D, null, 0xFFFFFFFFL);
            big.virtualSize = total;
            cam.objects.add(big);
            TransferEngine eng = engine(cam);
            ObjectInfo info = eng.listObjects(null).get(0);
            check("listing shows the overflow marker", eng.withRealSize(info).sizeOverflow());
            File dir = tmp();
            // Pretend the first 4 GiB - 1 MiB were already downloaded (sparse file, no real disk used).
            long already = (1L << 32) - 1024 * 1024;
            File part = new File(dir, "DSCF0008.MOV." + total + ".20261030T194500.part");
            try (RandomAccessFile r = new RandomAccessFile(part, "rw")) {
                r.setLength(already);
            }
            File out = eng.download(info, dir, null);
            check("final size is the real 64-bit size", out.length() == total);
            byte[] tail = new byte[(int) (total - already)];
            try (RandomAccessFile r = new RandomAccessFile(out, "r")) {
                r.seek(already);
                r.readFully(tail);
            }
            boolean same = true;
            for (int i = 0; i < tail.length && same; i++) same = tail[i] == FakeFujiCamera.Obj.virtualByte(already + i);
            check("bytes across the 4 GiB boundary are correct", same);
            check("standard op used below 4 GiB, extension op beyond (std=" + cam.stdPartialCalls.get()
                    + ", ext=" + cam.extPartialCalls.get() + ")", cam.stdPartialCalls.get() == 0 && cam.extPartialCalls.get() == 4); // 1 MiB chunks on Wi-Fi
            out.delete();
            eng.close();
        }
    }

    static void packetLinkReassembly() throws Exception {
        System.out.println("== USB-style packets (512 B, short packets, zero-length packets) reassemble into a stream");
        byte[] payload = FakeFujiCamera.pattern(5000, 9);
        Deque<byte[]> packets = new ArrayDeque<>();
        int pos = 0;
        int[] sizes = {512, 512, 7, 0, 512, 0, 0, 512};
        int k = 0;
        while (pos < payload.length) {
            int n = Math.min(sizes[k++ % sizes.length], payload.length - pos);
            packets.add(Arrays.copyOfRange(payload, pos, pos + n));
            pos += n;
        }
        PacketLink link = new PacketLink(new PacketLink.Bulk() {
            public int read(byte[] buf, int max, int timeout) throws IOException {
                byte[] p = packets.poll();
                if (p == null) throw new IOException("timeout");
                System.arraycopy(p, 0, buf, 0, p.length);
                return p.length;
            }

            public void write(byte[] b, int off, int len, int timeout) {
            }

            public void close() {
            }
        }, 512, 1000, 1000);
        byte[] got = new byte[payload.length];
        link.readFully(got, 0, 1000);
        link.readFully(got, 1000, 4000);
        check("bytes identical", Arrays.equals(got, payload));
    }

    static void thumbnails() throws Exception {
        System.out.println("== thumbnails on a generic PTP session (USB-like): 0x9055 first, GetThumb fallback");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.plainPtp = true;
            byte[] a = FakeFujiCamera.pattern(300_000, 11);
            byte[] b = FakeFujiCamera.pattern(400_000, 12);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0010.JPG", 0x3801, a, a.length));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0011.MOV", 0x300D, b, b.length));
            byte[] t = FakeFujiCamera.pattern(9000, 13);
            cam.thumbs.put("DSCF0010.JPG", t);
            TransferEngine eng = plainEngine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            check("thumbnail bytes via 0x9055", Arrays.equals(eng.thumbnail(list.get(0), true), t));
            check("0x9055 used, GetThumb not needed", cam.extThumbCalls.get() == 1 && cam.thumbCalls.get() == 0);
            check("object without thumbnail gives null", eng.thumbnail(list.get(1), true) == null);
            cam.extThumbSupported = false;
            check("fallback to GetThumb when 0x9055 is unsupported", Arrays.equals(eng.thumbnail(list.get(0), true), t));
            int ext = cam.extThumbCalls.get();
            eng.thumbnail(list.get(0), true);
            check("0x9055 not retried once known unsupported", cam.extThumbCalls.get() == ext);
            check("USB path uses GetThumb only", Arrays.equals(eng.thumbnail(list.get(0), false), t));
            check("session still usable for a download afterwards",
                    sha(eng.download(list.get(0), tmp(), listener(new AtomicInteger()))).equals(sha(a)));
            eng.close();
        }
    }

    static void thumbnailsAfterReconnect() throws Exception {
        System.out.println("== thumbnails after a reconnect that renumbered the handles: one re-listing, right images");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            List<byte[]> ts = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                byte[] d = FakeFujiCamera.pattern(50_000 + i, 20 + i);
                String n = String.format("DSCF%04d.JPG", 100 + i);
                cam.objects.add(new FakeFujiCamera.Obj(n, 0x3801, d, d.length));
                byte[] t = FakeFujiCamera.pattern(3000 + i, 40 + i);
                cam.thumbs.put(n, t);
                ts.add(t);
            }
            cam.shuffleHandlesPerConnection = true;
            TransferEngine eng = engine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            eng.close(); // next call opens a new session with different handles
            int infos = cam.objectInfoCalls.get();
            boolean all = true;
            for (int i = 0; i < list.size(); i++) all &= Arrays.equals(eng.thumbnail(list.get(i), false), ts.get(i));
            check("every thumbnail matches its file", all);
            int used = cam.objectInfoCalls.get() - infos; // 1 check + 6 re-listing + 6 info-before-thumb
            check("handles re-listed once, not once per thumbnail (" + used + " ObjectInfo calls)", used == 13);
            check("GetThumb always preceded by GetObjectInfo", cam.thumbsWithoutInfo.get() == 0);
            eng.close();
        }
    }

    static final FujiBle.Log QUIET = new FujiBle.Log() {
        public void log(String m) {
            System.out.println("   . " + m);
        }

        public void trace(String m) {
            if (System.getenv("BLE_TRACE") != null) System.out.println("     ~ " + m);
        }
    };

    static void bleLegacyPairing() throws Exception {
        System.out.println("== Bluetooth, older firmware: pairing key from the advertisement is written back");
        byte[] md = {2, 0x11, 0x22, 0x33, 0x44};
        FujiBle.Advert adv = FujiBle.Advert.parse("1234X-T4", "AA:00:00:00:00:01", md,
                Arrays.asList(FujiBle.SVC_FILE_TRANSFER.toLowerCase()));
        check("advert recognised as legacy pairing", adv != null && !adv.red && adv.pairingMode);
        check("short serial = first 4 chars of name", "1234".equals(adv.shortSerial));
        FakeBleCamera cam = new FakeBleCamera(false);
        cam.expectedKey = new byte[]{0x11, 0x22, 0x33, 0x44};
        FujiBle.Pairing p = FujiBle.pair(cam, adv, "Pixel 8", QUIET);
        check("key written unchanged", Arrays.equals(cam.writesTo(FujiBle.CH_PAIRING_KEY).get(0).value, cam.expectedKey));
        check("phone name written as UTF-8 + NUL",
                Arrays.equals(cam.writesTo(FujiBle.CH_DEVICE_NAME).get(0).value, FakeBleCamera.z("Pixel 8")));
        check("app info 80 01 01 written",
                Arrays.equals(cam.writesTo(FujiBle.CH_APP_INFO).get(0).value, new byte[]{(byte) 0x80, 1, 1}));
        FujiBle.Pairing back = FujiBle.Pairing.parse(p.serialize());
        check("pairing record survives save/load", back != null && Arrays.equals(back.pairingKey, p.pairingKey)
                && back.shortSerial.equals("1234") && !back.red);
        FujiBle.Advert later = FujiBle.Advert.parse("1234X-T4", "AA:00:00:00:00:99", null,
                Arrays.asList(FujiBle.SVC_FILE_TRANSFER));
        check("found again by serial even if the address changed", later != null && back.matches(later));
        FakeBleCamera cam2 = new FakeBleCamera(false);
        cam2.expectedKey = cam.expectedKey;
        FujiBle.reconnect(cam2, back, "Pixel 8", QUIET);
        check("reconnect writes the stored key", cam2.writesTo(FujiBle.CH_PAIRING_KEY).size() == 1);
        check("not-pairing advert refused for pairing", throwsIo(() -> FujiBle.pair(cam2, later, "x", QUIET)));
        check("legacy writes go to the legacy service", cam.writesToService(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION_RED).isEmpty()
                && FujiBle.serviceOf(FujiBle.CH_DEVICE_NAME, false).equals(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION));
        FakeBleCamera flaky = new FakeBleCamera(false);
        flaky.expectedKey = cam.expectedKey;
        flaky.transient133Writes = 2;
        FujiBle.pair(flaky, adv, "Pixel 8", QUIET);
        check("two 133 answers absorbed by retries, no bonding on legacy", flaky.bondRequests == 0
                && flaky.writesTo(FujiBle.CH_PAIRING_KEY).size() == 3);
        FakeBleCamera broken = new FakeBleCamera(false);
        broken.transient133Writes = 3;
        String m = message(() -> FujiBle.pair(broken, adv, "Pixel 8", QUIET));
        check("persistent 133 names the step (" + m + ")", m != null && m.contains("chiave di abbinamento") && m.contains("133"));
        check("other manufacturer data types ignored like XApp",
                FujiBle.Advert.parse("1234X", "a", new byte[]{1, 2, 3, 4, 5}, Arrays.asList(FujiBle.SVC_FILE_TRANSFER)) == null);
    }

    static void bleRedPairing() throws Exception {
        System.out.println("== Bluetooth, newer (RED) firmware: bonding, then identification number");
        byte[] md = {1, 'A', 'B', 'C', '1', '2'};
        FujiBle.Advert adv = FujiBle.Advert.parse("X-T5", "C0:00:00:00:00:05", md,
                Arrays.asList(FujiBle.SVC_CAMERA_INFORMATION_RED));
        check("advert recognised as RED pairing", adv != null && adv.red && adv.pairingMode && "ABC12".equals(adv.shortSerial));
        FakeBleCamera cam = new FakeBleCamera(true);
        cam.zeroReadsBeforeConfirm = 2; // user still has to press OK on the camera
        FujiBle.Pairing p = FujiBle.pair(cam, adv, "Pixel 8", QUIET);
        check("bond requested when the camera demanded encryption", cam.bondRequests == 1);
        long expected = 0x1234L | 0x20000000L;
        check("id = camera number | 0x20000000 (" + Long.toHexString(p.identificationNumber) + ")",
                p.identificationNumber == expected);
        byte[] w = cam.writesTo(FujiBle.CH_IDENTIFICATION_NUMBER).get(0).value;
        check("id written back little-endian", w.length == 4 && Ptp.u32(w, 0) == expected);
        check("waited for the confirmation instead of failing", cam.sleptMs >= 2000);
        check("all pairing writes went to the RED service",
                cam.writesToService(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION).isEmpty()
                        && cam.writesToService(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION_RED).size() >= 3);
        FakeBleCamera cam2 = new FakeBleCamera(true);
        cam2.bonded = true;
        cam2.cameraNumber = 0x9999;
        FujiBle.Pairing again = FujiBle.reconnect(cam2, FujiBle.Pairing.parse(p.serialize()), "Pixel 8", QUIET);
        check("reconnect writes the stored id, not a new one",
                Ptp.u32(cam2.writesTo(FujiBle.CH_IDENTIFICATION_NUMBER).get(0).value, 0) == expected
                        && again.identificationNumber == expected);
        check("reconnect does not write the name before the id", cam2.writes.get(0).ch.equals(FujiBle.CH_IDENTIFICATION_NUMBER));
        System.out.println("   -- RED body that still exposes the legacy service (writes there fail with 133)");
        FakeBleCamera decoy = new FakeBleCamera(true).withLegacyDecoy();
        FujiBle.Pairing pd = FujiBle.pair(decoy, adv, "Pixel 8", QUIET);
        check("legacy service never written on a RED body", decoy.writesToService(FujiBle.SVC_CONNECTED_DEVICE_INFORMATION).isEmpty());
        check("pairing completes", pd.identificationNumber == expected);
        FujiBle.launchWifi(decoy, true, 5000, QUIET);
        check("Wi-Fi launch works on that body", decoy.launchRequests == 1);
        System.out.println("   -- phone reports 'needs encryption' as 133");
        FakeBleCamera as133 = new FakeBleCamera(true);
        as133.unbondedStatus = 133;
        FujiBle.Pairing p133 = FujiBle.pair(as133, adv, "Pixel 8", QUIET);
        check("bonds and completes (bond requests " + as133.bondRequests + ")", as133.bondRequests == 1
                && p133.identificationNumber == expected);
        FakeBleCamera never = new FakeBleCamera(true);
        never.bonded = true;
        never.zeroReadsBeforeConfirm = 1000;
        check("no confirmation within 40 tries -> clear error", throwsIo(() -> FujiBle.pair(never, adv, "x", QUIET)));
    }

    static void metaProbe() throws Exception {
        System.out.println("== metadata probe: 0x9805 / 0x9054 answers logged, real sizes compared, nothing breaks");
        byte[] list = new byte[4 + 2 * 12];
        Ptp.putU32(list, 0, 2);
        Ptp.putU32(list, 4, 1);
        Ptp.putU16(list, 8, Ptp.OPC_OBJECT_SIZE);
        Ptp.putU16(list, 10, 0x0006);
        Ptp.putU32(list, 12, 123456);
        Ptp.putU32(list, 16, 2);
        Ptp.putU16(list, 20, Ptp.OPC_OBJECT_SIZE);
        Ptp.putU16(list, 22, 0x0006);
        Ptp.putU32(list, 24, 789);
        List<String> items = new java.util.ArrayList<>();
        check("ObjectPropList parsed (2 sizes)", MetaProbe.parsePropList(list, items, 8) == 2
                && items.get(0).equals("1:DC04=123456") && items.get(1).equals("2:DC04=789"));
        check("truncated ObjectPropList -> -1", MetaProbe.parsePropList(Arrays.copyOf(list, 20), new java.util.ArrayList<>(), 8) == -1);
        byte[] str = {0, 4, 'A', 0, 'B', 0, 'C', 0, 0, 0, 9};
        check("PTP strings found in raw bytes", MetaProbe.strings(str).equals(List.of("@1 \"ABC\"")));
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] jpg = FakeFujiCamera.pattern(200_000, 3);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, jpg, jpg.length));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0002.JPG", 0x3801, jpg, jpg.length));
            TransferEngine eng = engine(cam);
            List<ObjectInfo> objs = eng.listObjects(null);
            List<String> log = new java.util.ArrayList<>();
            MetaProbe.run(eng, objs, log::add);
            String all = String.join("\n", log);
            check("probe runs to the end on a camera without 0x9805", all.contains("0x9054 su 2 file in fila")
                    && all.contains("0x9805 per tutti saltato") && all.contains("prova metadati: finita"));
            check("probe logs the real size to compare", all.contains("dimensione vera DSCF0001.JPG = " + jpg.length + " B"));
            check("session still usable after the probe", eng.download(objs.get(1), tmp(), null).length() == jpg.length);
        }
    }

    static void bleXAppPreLaunchSequence() throws Exception {
        System.out.println("== before the Wi-Fi request: XApp's subscriptions and writes (from the X-M5 Bluetooth log)");
        FakeBleCamera cam = new FakeBleCamera(true);
        cam.addXAppExtras();
        FujiBle.launchWifi(cam, true, 5000, QUIET);
        List<String> order = new java.util.ArrayList<>();
        for (FakeBleCamera.Write w : cam.writes) order.add(w.ch);
        int ex = order.indexOf(FujiBle.CH_IMAGE_TRANSFER_SETTING_EX);
        int utc = order.indexOf(FujiBle.CH_UTC_AND_TIMEZONE);
        int launch = order.indexOf(FujiBle.CH_FUNCTION_LAUNCH_REQUEST);
        check("UTC first, then image transfer setting ex <- 01, then the Wi-Fi request", utc >= 0 && ex > utc
                && launch > ex && Arrays.equals(cam.writes.get(ex).value, new byte[]{1}));
        check("time written once when the camera does not ask for it",
                order.lastIndexOf(FujiBle.CH_UTC_AND_TIMEZONE) == utc);
        FakeBleCamera asking = new FakeBleCamera(true);
        asking.addXAppExtras();
        asking.asksDateOnSubscribe = true;
        FujiBle.launchWifi(asking, true, 5000, QUIET);
        List<String> askOrder = new java.util.ArrayList<>();
        for (FakeBleCamera.Write w : asking.writes) askOrder.add(w.ch);
        int again = askOrder.lastIndexOf(FujiBle.CH_UTC_AND_TIMEZONE);
        check("camera asks for the date: time sent again before the Wi-Fi request, like XApp",
                again > askOrder.indexOf(FujiBle.CH_UTC_AND_TIMEZONE)
                        && again < askOrder.indexOf(FujiBle.CH_FUNCTION_LAUNCH_REQUEST));
        check("subscribed to transfer state and camera vital state like XApp",
                cam.subscribed.contains(FujiBle.CH_TRANSFER_STATE) && cam.subscribed.contains(FujiBle.CH_CAMERA_VITAL_STATE)
                        && cam.subscribed.indexOf(FujiBle.CH_AP_STATE) == 0);
        java.util.TimeZone rome = java.util.TimeZone.getTimeZone("Europe/Rome");
        byte[] t = FujiBle.utcAndTimeZone(1793648400000L, rome); // 2026-11-02 19:40 UTC
        check("UTC + zone for Rome in winter: 2026-11-02 19:40:00, offset 100, no DST",
                bytesToHex(t).equals("ea070b02132800" + "64000000" + "01"));
        byte[] india = FujiBle.utcAndTimeZone(1793648400000L, java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        check("+5:30 encoded as 550", Ptp.u32(india, 7) == 550);
        FakeBleCamera plain = new FakeBleCamera(true); // body without these characteristics
        check("bodies without them still launch", FujiBle.launchWifi(plain, true, 5000, QUIET).ssid.length() > 0);
    }

    static void bleWifiLaunch() throws Exception {
        System.out.println("== Bluetooth asks the camera to start Wi-Fi (InCameraViewing = 3)");
        FakeBleCamera cam = new FakeBleCamera(true);
        FujiBle.WifiCredentials c = FujiBle.launchWifi(cam, true, 5000, QUIET);
        check("launch request 03 00", Arrays.equals(cam.writesTo(FujiBle.CH_FUNCTION_LAUNCH_REQUEST).get(0).value, new byte[]{3, 0}));
        check("SSID, passphrase and BSSID read", "FUJIFILM-X-T5-ABCD".equals(c.ssid) && "secret123".equals(c.passphrase)
                && "AA:BB:CC:DD:EE:FF".equals(c.bssid));
        FakeBleCamera busy = new FakeBleCamera(true);
        busy.busyAnswers = 2;
        FujiBle.launchWifi(busy, true, 5000, QUIET);
        check("camera busy for a moment -> request repeated (" + busy.launchRequests + ")", busy.launchRequests == 3);
        FakeBleCamera quick = new FakeBleCamera(true);
        quick.busyAnswers = 3;
        quick.quietRefusal = true;
        FujiBle.WifiCredentials qc = FujiBle.launchWifi(quick, true, 5000, QUIET);
        check("refused at once (busy) -> repeated each time, then accepted (" + quick.launchRequests + " requests)",
                quick.launchRequests == 4 && "FUJIFILM-X-T5-ABCD".equals(qc.ssid));
        // the reason is read right after each refused request: "W E" and not "W S S E" (quiet polls first)
        boolean immediate = true;
        int lastW = quick.launchOps.lastIndexOf("W");
        for (int i = 0; i < lastW; i++) {
            if (quick.launchOps.get(i).equals("W")) immediate &= quick.launchOps.get(i + 1).equals("E");
        }
        check("refusal reason read at once, no quiet polls first " + quick.launchOps, immediate && lastW >= 3);
        FakeBleCamera silent = new FakeBleCamera(true);
        silent.deliverNotifications = false;
        check("works without notifications by polling the state", FujiBle.launchWifi(silent, true, 5000, QUIET).ssid.length() > 0);
        FakeBleCamera noCccd = new FakeBleCamera(false);
        noCccd.notificationsFail = true;
        check("notification setup failing is not fatal", FujiBle.launchWifi(noCccd, false, 5000, QUIET).ssid.length() > 0);
        FakeBleCamera usb = new FakeBleCamera(true);
        usb.refuseWith = 6;
        String msg = message(() -> FujiBle.launchWifi(usb, true, 5000, QUIET));
        check("refusal explained (" + msg + ")", msg != null && msg.contains("USB"));
        FakeBleCamera stuck = new FakeBleCamera(true) {
            @Override
            public void write(String svc, String ch, byte[] v) {
                writes.add(new Write(svc, ch, v)); // accepts the request but never starts the access point
            }
        };
        long t0 = System.currentTimeMillis();
        check("no answer -> timeout, not a hang", throwsIo(() -> FujiBle.launchWifi(stuck, true, 1500, QUIET))
                && System.currentTimeMillis() - t0 < 5000);
        check("MAC normalisation rejects junk", FujiBle.normalizeMac("not a mac").isEmpty());
    }

    static void bleAdoptXAppPairing() throws Exception {
        System.out.println("== camera already paired with XApp: reuse it instead of pairing again");
        FakeBleCamera red = new FakeBleCamera(true);
        red.bonded = true; // the phone is bonded at OS level, for every app
        FujiBle.Pairing p = FujiBle.adopt(red, "C0:00:00:00:00:05", "X-T5", "Pixel 8", QUIET);
        long expected = 0x1234L | 0x20000000L;
        check("RED: same id XApp writes (number | 0x20000000)", p.red && p.identificationNumber == expected
                && Ptp.u32(red.writesTo(FujiBle.CH_IDENTIFICATION_NUMBER).get(0).value, 0) == expected);
        check("RED: no new registration (no bond request, id written first)", red.bondRequests == 0
                && red.writes.get(0).ch.equals(FujiBle.CH_IDENTIFICATION_NUMBER));
        FakeBleCamera stranger = new FakeBleCamera(true);
        stranger.bonded = true;
        stranger.zeroReadsBeforeConfirm = 1000;
        long t0 = System.currentTimeMillis();
        String m = message(() -> FujiBle.adopt(stranger, "a", "X-T5", "x", QUIET));
        check("RED not paired with this phone -> quick clear message (" + m + ")",
                m != null && m.contains("Abbina nuova camera") && stranger.sleptMs <= 6000);
        FakeBleCamera old = new FakeBleCamera(false);
        FujiBle.Pairing lp = FujiBle.adopt(old, "AA:00:00:00:00:01", "1234X-T4", "Pixel 8", QUIET);
        check("legacy: adopted without key, nothing written", !lp.red && lp.pairingKey == null && old.writes.isEmpty());
        FujiBle.Pairing lp2 = FujiBle.Pairing.parse(lp.serialize());
        FujiBle.reconnect(old, lp2, "Pixel 8", QUIET);
        check("legacy without key: reconnect writes nothing, Wi-Fi launch still possible",
                old.writes.isEmpty() && FujiBle.launchWifi(old, false, 5000, QUIET).ssid.length() > 0);
        FakeBleCamera readable = new FakeBleCamera(false);
        byte[] key = {9, 8, 7, 6};
        readable.values.put(FujiBle.CH_PAIRING_KEY, key);
        FujiBle.Pairing kp = FujiBle.adopt(readable, "AA:00:00:00:00:02", "1234X-T4", "Pixel 8", QUIET);
        check("legacy: key used when the camera lets us read it", Arrays.equals(kp.pairingKey, key)
                && Arrays.equals(readable.writesTo(FujiBle.CH_PAIRING_KEY).get(0).value, key));
        FakeBleCamera watch = new FakeBleCamera(false);
        watch.chars.clear();
        check("not a camera -> refused", throwsIo(() -> FujiBle.adopt(watch, "b", "Watch", "x", QUIET)));
    }

    interface IoCall {
        Object run() throws Exception;
    }

    static boolean throwsIo(IoCall c) {
        return message(c) != null;
    }

    static String message(IoCall c) {
        try {
            c.run();
            return null;
        } catch (IOException e) {
            return e.getMessage();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Fuji Wi-Fi session (fudge / XApp)

    static final class Notes implements FujiWifi.Progress {
        final AtomicInteger waiting = new AtomicInteger();
        final AtomicInteger slow = new AtomicInteger();

        public void trace(String m) {
            if (System.getenv("BLE_TRACE") != null) System.out.println("     ~ " + m);
        }

        public void waitingForAccess() {
            waiting.incrementAndGet();
        }

        public void slowModeChange() {
            slow.incrementAndGet();
        }
    }

    static int indexOfEntry(List<String> log, String prefix) {
        synchronized (log) {
            for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    static boolean logged(FakeFujiCamera cam, String prefix) {
        return indexOfEntry(cam.log, prefix) >= 0;
    }

    static void xappSessionSetup() throws Exception {
        System.out.println("== X-M5 after Bluetooth hand-off: the session set-up XApp and fudge do");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] a = FakeFujiCamera.pattern(3 * 1024 * 1024 + 7, 61);
            byte[] b = FakeFujiCamera.pattern(700_000, 62);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.RAF", 0xB103, a, a.length));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0002.JPG", 0x3801, b, b.length));
            byte[] t = FakeFujiCamera.pattern(5000, 63);
            cam.thumbs.put("DSCF0002.JPG", t);
            cam.accessPolls = 20; // CameraState 0 for ~2 s
            Notes notes = new Notes();
            TransferEngine eng = engine(cam, new FujiWifi.Options(), notes);
            List<ObjectInfo> list = eng.listObjects(null);
            FujiWifi f = eng.session().fujiWifi();
            check("XApp mode chosen (remote version 0x2000B)", f != null && f.isXapp());
            check("OpenSession is the first command, transaction 1", cam.firstOpenSessionTid == 1
                    && cam.log.get(0).equals("1002 tid=1"));
            check("no GetDeviceInfo on Fuji Wi-Fi", cam.getDeviceInfoCalls.get() == 0);
            check("waited for CameraState to grant access, note shown once", notes.waiting.get() == 1);
            int cs = indexOfEntry(cam.log, "1016 DF01=20");
            check("ClientState 20 (remote image view, XApp) set after the access wait", cs > indexOfEntry(cam.log, "1015 DF24"));
            check("gallery set-up: DF28 echoed, D226 = 0, D227 = 0", logged(cam, "1016 DF28=3")
                    && logged(cam, "1016 D226=0") && logged(cam, "1016 D227=0"));
            check("nothing ignored by the camera", cam.ignoredBeforeSetup.get() == 0);
            check("two files from handles 1..2", list.size() == 2 && list.get(0).handle == 1 && list.get(1).handle == 2);
            check("listed in gallery mode like XApp (no D226 = 2 before the first download)", !logged(cam, "1016 D226=2"));
            check("real sizes when asked, not the placeholder", eng.withRealSize(list.get(0)).size == a.length
                    && eng.withRealSize(list.get(1)).size == b.length);
            check("thumbnail via GetObjectInfo + GetThumb", Arrays.equals(eng.thumbnail(list.get(1), true), t)
                    && cam.thumbsWithoutInfo.get() == 0);
            check("no thumbnail -> null", eng.thumbnail(list.get(0), true) == null);
            File out = eng.download(list.get(0), tmp(), null);
            check("download matches", sha(out).equals(sha(a)));
            check("downloaded the original (D226 = 2), not a reduced copy", cam.downloadsNotOriginal.get() == 0);
            eng.close();
        }
    }

    static void fastListing() throws Exception {
        System.out.println("== big card over Wi-Fi: newest first, partial results, resume after a drop, reuse of the last listing");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 60; i++) {
                cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[100 + i], 100 + i));
            }
            cam.dropAfterObjectInfos = 30;
            TransferEngine eng = engine(cam);
            List<Integer> firstHandles = new java.util.ArrayList<>();
            AtomicInteger partials = new AtomicInteger();
            List<ObjectInfo> list = eng.listObjects(listener(new AtomicInteger()), null, (soFar, done, total) -> {
                if (partials.getAndIncrement() == 0 && !soFar.isEmpty()) firstHandles.add((int) soFar.get(soFar.size() - 1).handle);
            });
            check("all 60 listed despite a drop at 30", list.size() == 60);
            int used = cam.objectInfoCalls.get();
            check("retry continued instead of starting over (" + used + " ObjectInfo calls for 60)", used <= 60 + 2 + 2 + 2 + 8); // a drop loses at most one group in flight
            check("partial results delivered", partials.get() >= 1);
            check("newest first: the highest handle comes first", !firstHandles.isEmpty() && firstHandles.get(0) == 60);
            eng.close();

            // next connection: two new shots
            List<String> lines = new java.util.ArrayList<>();
            for (ObjectInfo i : list) lines.add(i.toLine());
            List<ObjectInfo> cached = new java.util.ArrayList<>();
            for (String line : lines) cached.add(ObjectInfo.fromLine(line));
            check("cache line round trip", cached.get(5).sameFileAs(list.get(5)) && cached.get(5).handle == list.get(5).handle);
            for (int i = 61; i <= 62; i++) {
                cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[100 + i], 100 + i));
            }
            TransferEngine eng2 = engine(cam);
            int before = cam.objectInfoCalls.get();
            List<ObjectInfo> list2 = eng2.listObjects(null, cached, null);
            int used2 = cam.objectInfoCalls.get() - before;
            check("62 listed", list2.size() == 62);
            check("only the 2 new shots read, plus 2 checks (" + used2 + " calls)", used2 == 4);
            eng2.close();

            // a deletion renumbers everything: the cache must not be trusted
            cam.objects.remove(10);
            TransferEngine eng3 = engine(cam);
            List<ObjectInfo> list3 = eng3.listObjects(null, list2, null);
            boolean right = true;
            for (ObjectInfo i : list3) right &= i.filename.equals(cam.objects.get((int) i.handle - 1).name);
            check("after a deletion the listing is re-read and correct", list3.size() == 61 && right);
            eng3.close();
        }
    }

    static void cacheCheckAvoidsRaf() throws Exception {
        System.out.println("== listing cache check reads a JPEG, not the old RAF at the bottom (1.6-2.0 s on the X-M5)");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 10; i++) {
                boolean raf = i % 2 == 1; // handle 1 is a RAF, 2 its JPEG, ...
                cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.%s", i, raf ? "RAF" : "JPG"),
                        raf ? 0xB103 : 0x3801, new byte[100 + i], 100 + i));
            }
            TransferEngine eng = engine(cam);
            List<ObjectInfo> first = eng.listObjects(null);
            eng.close();
            cam.objectInfoHandles.clear();
            TransferEngine eng2 = engine(cam);
            List<ObjectInfo> again = eng2.listObjects(null, first, null);
            List<Long> asked;
            synchronized (cam.objectInfoHandles) {
                asked = new java.util.ArrayList<>(cam.objectInfoHandles);
            }
            check("10 listed from the cache", again.size() == 10);
            check("the check read handle 2 (JPG) and the newest, not handle 1 (RAF): " + asked,
                    asked.equals(java.util.Arrays.asList(2L, 10L)));
            eng2.close();

            // only RAFs: nothing cheaper to read, the lowest one is checked as before
            try (FakeFujiCamera raws = new FakeFujiCamera()) {
                for (int i = 1; i <= 4; i++) {
                    raws.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.RAF", i), 0xB103, new byte[100 + i], 100 + i));
                }
                TransferEngine r1 = engine(raws);
                List<ObjectInfo> l1 = r1.listObjects(null);
                r1.close();
                raws.objectInfoHandles.clear();
                TransferEngine r2 = engine(raws);
                r2.listObjects(null, l1, null);
                List<Long> askedRaw;
                synchronized (raws.objectInfoHandles) {
                    askedRaw = new java.util.ArrayList<>(raws.objectInfoHandles);
                }
                check("only RAFs: lowest and newest as before: " + askedRaw, askedRaw.equals(java.util.Arrays.asList(1L, 4L)));
                r2.close();
            }
        }
    }

    static void listingWithoutSlowMode() throws Exception {
        System.out.println("== real-size mode slow, plain mode same sizes: list in plain mode, download still the original");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] a = FakeFujiCamera.pattern(1024 * 1024 + 9, 91);
            for (int i = 0; i < 20; i++) cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, a, a.length));
            cam.realModeInfoDelayMs = 150;
            cam.plainSizesReal = true;
            TransferEngine eng = engine(cam);
            long t0 = System.currentTimeMillis();
            List<ObjectInfo> list = eng.listObjects(null);
            long ms = System.currentTimeMillis() - t0;
            check("20 listed in plain mode, fast (" + ms + " ms; real mode would be > 3000)", list.size() == 20 && ms < 2500);
            File out = eng.download(list.get(0), tmp(), null);
            check("download is the original file", sha(out).equals(sha(a)) && cam.downloadsNotOriginal.get() == 0);
            eng.close();
        }
    }

    static void pipelinedListing() throws Exception {
        System.out.println("== listing in blocks of 4 GetObjectInfo in flight; a camera that cannot cope gets one at a time");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 41; i++) cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[50 + i], 50 + i));
            TransferEngine eng = engine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            boolean right = list.size() == 41;
            for (ObjectInfo i : list) right &= i.filename.equals(cam.objects.get((int) i.handle - 1).name);
            check("41 listed correctly (not a multiple of 4)", right);
            check("requests really were queued (" + cam.queuedCommandsSeen.get() + ")", cam.queuedCommandsSeen.get() >= 20);
            eng.close();
        }
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 12; i++) cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[50 + i], 50 + i));
            cam.rejectPipelining = true;
            TransferEngine eng = engine(cam);
            AtomicInteger retries = new AtomicInteger();
            List<ObjectInfo> list = eng.listObjects(listener(retries));
            check("camera hangs on queued requests -> one reconnect, then one at a time, all 12 listed",
                    list.size() == 12 && retries.get() == 1);
            eng.close();
        }
    }

    /** A JPEG start with EXIF: GPS 45°27'36"N 9°11'24"E (Milan) and a Fuji maker note (Classic Chrome). */
    static byte[] exifJpeg(boolean south, boolean acros) {
        java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
        // TIFF, little-endian: header, IFD0 at 8 with 2 entries (ExifIFD, GPS), then the two IFDs and data
        int ifd0 = 8;
        int exifIfd = ifd0 + 2 + 2 * 12 + 4;          // 38
        int gpsIfd = exifIfd + 2 + 12 + 4;            // 56
        int gpsData = gpsIfd + 2 + 4 * 12 + 4;        // 110
        int maker = gpsData + 48;                     // 158
        int makerLen = 12 + 2 + 2 * 12 + 4;
        w16(t, 0x4949); w16(t, 42); w32(t, ifd0);
        w16(t, 2);
        w16(t, 0x8769); w16(t, 4); w32(t, 1); w32(t, exifIfd);
        w16(t, 0x8825); w16(t, 4); w32(t, 1); w32(t, gpsIfd);
        w32(t, 0);
        w16(t, 1);
        w16(t, 0x927C); w16(t, 7); w32(t, makerLen); w32(t, maker);
        w32(t, 0);
        w16(t, 4);
        w16(t, 1); w16(t, 2); w32(t, 2); w32(t, south ? 'S' : 'N');
        w16(t, 2); w16(t, 5); w32(t, 3); w32(t, gpsData);
        w16(t, 3); w16(t, 2); w32(t, 2); w32(t, 'E');
        w16(t, 4); w16(t, 5); w32(t, 3); w32(t, gpsData + 24);
        w32(t, 0);
        long[] lat = {45, 1, 27, 1, 36, 1};
        long[] lon = {9, 1, 11, 1, 2400, 100};
        for (long v : lat) w32(t, v);
        for (long v : lon) w32(t, v);
        for (char c : "FUJIFILM".toCharArray()) t.write(c);
        w32(t, 12);
        w16(t, 2);
        w16(t, 0x1003); w16(t, 3); w32(t, 1); w32(t, acros ? 0x501 : 0);
        w16(t, 0x1401); w16(t, 3); w32(t, 1); w32(t, 0x600);
        w32(t, 0);
        byte[] tiff = t.toByteArray();
        java.io.ByteArrayOutputStream j = new java.io.ByteArrayOutputStream();
        j.write(0xFF); j.write(0xD8);
        j.write(0xFF); j.write(0xE1);
        int len = 2 + 6 + tiff.length;
        j.write(len >> 8); j.write(len & 0xFF);
        for (char c : "Exif".toCharArray()) j.write(c);
        j.write(0); j.write(0);
        j.write(tiff, 0, tiff.length);
        j.write(0xFF); j.write(0xDA);
        byte[] filler = FakeFujiCamera.pattern(90_000, 3); // image data after the metadata
        j.write(filler, 0, filler.length);
        return j.toByteArray();
    }

    static void w16(java.io.ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >> 8) & 0xFF);
    }

    static void w32(java.io.ByteArrayOutputStream b, long v) {
        writeU32(b, v);
    }

    /** RAF header pointing at an embedded JPEG (big-endian offset/length at 84/88). */
    static byte[] raf(byte[] jpeg) {
        byte[] r = new byte[512 + jpeg.length];
        byte[] magic = "FUJIFILMCCD-RAW 0201".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, r, 0, magic.length);
        int off = 512;
        r[84] = (byte) (off >> 24); r[85] = (byte) (off >> 16); r[86] = (byte) (off >> 8); r[87] = (byte) off;
        int len = jpeg.length;
        r[88] = (byte) (len >> 24); r[89] = (byte) (len >> 16); r[90] = (byte) (len >> 8); r[91] = (byte) len;
        System.arraycopy(jpeg, 0, r, off, jpeg.length);
        return r;
    }

    static void listingPriority() throws Exception {
        System.out.println("== listing reads the handles the app asks for first (days on screen), then the rest");
        java.util.List<Long> order = new java.util.ArrayList<>(java.util.Arrays.asList(5L, 3L, 9L));
        ObjectLister.promote(order, 1, 1, java.util.Arrays.asList(9L));
        check("promote moves the wanted handle to the next slot", order.equals(java.util.Arrays.asList(5L, 9L, 3L)));
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 30; i++) cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[40 + i], 40 + i));
            TransferEngine eng = engine(cam);
            final boolean[] earlyOld = {false};
            List<ObjectInfo> list = eng.listObjects(null, null, new TransferEngine.ListingListener() {
                @Override
                public void onPartial(List<ObjectInfo> soFar, int done, int total) {
                    if (done <= 4) for (ObjectInfo o : soFar) if (o.handle == 2) earlyOld[0] = true;
                }

                @Override
                public List<Long> priority() {
                    return java.util.Arrays.asList(2L, 1L);
                }
            });
            check("all 30 listed", list.size() == 30);
            check("handle 2 (oldest day) read among the first files, not last", earlyOld[0]);
            eng.close();
        }
    }

    static void exifReading() throws Exception {
        System.out.println("== EXIF from the first 64 KiB: GPS and film simulation, JPEG and RAF");
        Exif.Info i = Exif.parse(exifJpeg(false, false));
        check("position parsed (45.46, 9.19)", i.hasPosition() && Math.abs(i.lat - 45.46) < 0.001 && Math.abs(i.lon - 9.19) < 0.001);
        check("film simulation from the Fuji maker note", "Classic Chrome".equals(i.film));
        Exif.Info s = Exif.parse(exifJpeg(true, true));
        check("southern latitude negative, monochrome wins over film mode", s.lat < 0 && "Acros +R".equals(s.film));
        check("garbage gives an empty result", !Exif.parse(new byte[]{1, 2, 3}).hasPosition());
        check("truncated EXIF does not throw", Exif.parse(java.util.Arrays.copyOf(exifJpeg(false, false), 120)) != null);
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] jpg = exifJpeg(false, false);
            byte[] rafData = raf(exifJpeg(true, false));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, jpg, jpg.length));
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0002.RAF", 0xB103, rafData, rafData.length));
            TransferEngine eng = engine(cam);
            List<ObjectInfo> list = eng.listObjects(null);
            List<Exif.Info> got = eng.readExif(list);
            Exif.Info a = got.get(list.get(0).filename.endsWith("JPG") ? 0 : 1);
            Exif.Info b = got.get(list.get(0).filename.endsWith("JPG") ? 1 : 0);
            check("JPEG read from the camera", a != null && a.hasPosition() && a.lat > 0);
            check("RAF read at its embedded JPEG", b != null && b.hasPosition() && b.lat < 0);
            check("original-file mode used for the reads", cam.downloadsNotOriginal.get() == 0);
            eng.close();
        }
        Exif.Info o = Exif.parse(orientedJpeg(6, true));
        check("orientation read (6, little-endian)", o.orientation == 6);
        check("orientation read (8, big-endian)", Exif.parse(orientedJpeg(8, false)).orientation == 8);
        check("no orientation tag -> 0", Exif.parse(exifJpeg(false, false)).orientation == 0);
    }

    /** A JPEG whose EXIF has only an IFD0 with the Orientation tag. */
    static byte[] orientedJpeg(int orientation, boolean le) {
        java.io.ByteArrayOutputStream t = new java.io.ByteArrayOutputStream();
        java.util.function.IntConsumer u16 = v -> {
            if (le) { t.write(v); t.write(v >> 8); } else { t.write(v >> 8); t.write(v); }
        };
        java.util.function.LongConsumer u32 = v -> {
            if (le) { t.write((int) v); t.write((int) (v >> 8)); t.write((int) (v >> 16)); t.write((int) (v >> 24)); }
            else { t.write((int) (v >> 24)); t.write((int) (v >> 16)); t.write((int) (v >> 8)); t.write((int) v); }
        };
        t.write(le ? 'I' : 'M'); t.write(le ? 'I' : 'M'); u16.accept(42); u32.accept(8);
        u16.accept(1); // one entry
        u16.accept(0x0112); u16.accept(3); u32.accept(1); u16.accept(orientation); u16.accept(0);
        u32.accept(0); // no next IFD
        byte[] tiff = t.toByteArray();
        java.io.ByteArrayOutputStream j = new java.io.ByteArrayOutputStream();
        j.write(0xFF); j.write(0xD8); j.write(0xFF); j.write(0xE1);
        int len = 2 + 6 + tiff.length;
        j.write(len >> 8); j.write(len);
        j.write('E'); j.write('x'); j.write('i'); j.write('f'); j.write(0); j.write(0);
        j.write(tiff, 0, tiff.length);
        j.write(0xFF); j.write(0xD9);
        return j.toByteArray();
    }

    static void dateGroupsLayout() {
        System.out.println("== 0x9053 reply as XApp's libFTLPTPIP reads it: u32 days, then {u32, PTP string date, u32 count}");
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        String[] dates = {"20261102", "20261031"};
        int[] counts = {12, 433};
        writeU32(b, 2);
        for (int i = 0; i < 2; i++) {
            writeU32(b, 0xFFFF);
            b.write(dates[i].length() + 1);
            for (char c : (dates[i] + "\0").toCharArray()) {
                b.write(c & 0xFF);
                b.write(c >> 8);
            }
            writeU32(b, counts[i]);
        }
        List<FujiWifi.DateGroup> g = FujiWifi.parseDateGroups(b.toByteArray());
        check("two days parsed", g != null && g.size() == 2);
        check("dates and counts", g != null && g.get(0).date.equals("20261102") && g.get(0).count == 12
                && g.get(1).date.equals("20261031") && g.get(1).count == 433);
        check("truncated reply rejected", FujiWifi.parseDateGroups(java.util.Arrays.copyOf(b.toByteArray(), 20)) == null);
        java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
        writeU32(h, 3);
        writeU32(h, 444);
        writeU32(h, 445);
        writeU32(h, 442);
        List<Long> hs = FujiWifi.parseHandleArray(h.toByteArray());
        check("0xD621 handle array (u32 count + handles), order kept", hs != null && hs.equals(java.util.Arrays.asList(444L, 445L, 442L)));
        check("0xD621 with a wrong length rejected", FujiWifi.parseHandleArray(java.util.Arrays.copyOf(h.toByteArray(), 14)) == null);
    }

    static void writeU32(java.io.ByteArrayOutputStream b, long v) {
        for (int i = 0; i < 4; i++) b.write((int) (v >> (8 * i)) & 0xFF);
    }

    static void shortPartialReplies() throws Exception {
        System.out.println("== camera sends 32 bytes less than asked per chunk (X-M5 on a MOV): download goes on");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] d = FakeFujiCamera.pattern(5 * 1024 * 1024 + 77, 11);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0011.MOV", 0x300D, d, d.length));
            cam.partialCap = 1024 * 1024 - 32;
            TransferEngine eng = engine(cam);
            AtomicInteger retries = new AtomicInteger();
            File out = eng.download(eng.listObjects(null).get(0), tmp(), listener(retries));
            check("file complete and identical", sha(out).equals(sha(d)));
            check("no reconnect for a short chunk (" + retries.get() + " retries)", retries.get() == 0);
            eng.close();
        }
    }

    static void downloadIntoChannel() throws Exception {
        System.out.println("== download into the caller's channel (media store item): one GetObjectInfo, resume after what it holds");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] a = FakeFujiCamera.pattern(3 * 1024 * 1024 + 11, 71);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0071.JPG", 0x3801, a, a.length));
            TransferEngine eng = engine(cam, new FujiWifi.Options(), new Notes());
            ObjectInfo listed = eng.listObjects(null).get(0);
            check("listing carries the placeholder size", listed.size == FakeFujiCamera.PLACEHOLDER_SIZE);
            int infos = cam.objectInfoCalls.get();
            ObjectInfo sized = eng.sizeForDownload(listed);
            check("real size before the first byte", sized.size == a.length);
            File f = new File(tmp(), "item");
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                raf.write(a, 0, 1024 * 1024); // what an interrupted attempt left in the item
                long sent = cam.dataBytesSent.get();
                eng.download(sized, raf.getChannel(), listener(new AtomicInteger()));
                check("only the missing part sent", cam.dataBytesSent.get() - sent == a.length - 1024 * 1024);
            }
            check("file complete and identical", sha(f).equals(sha(a)));
            int used = cam.objectInfoCalls.get() - infos;
            check("one GetObjectInfo for size + download (" + used + ")", used == 1);
            check("original files read", cam.downloadsNotOriginal.get() == 0);
            int before = cam.objectInfoCalls.get();
            ObjectInfo again = eng.sizeForDownload(listed);
            File g = eng.download(again, tmp(), listener(new AtomicInteger()));
            check("file download still works from a gallery listing", sha(g).equals(sha(a)));
            check("sized once, then downloaded without asking again (" + (cam.objectInfoCalls.get() - before) + ")",
                    cam.objectInfoCalls.get() - before == 1);
            eng.close();
        }
    }

    static void viewerPreviews() throws Exception {
        System.out.println("== viewer: RAF from its embedded JPEG, size-S copy from the camera like XApp's zoom view");
        java.util.List<String> wire = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        PtpSession.wire = wire::add;
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] inner = jpegOf(200_000, 81);
            byte[] head = raf(inner);
            byte[] rafData = Arrays.copyOf(head, head.length + 3_000_000); // the raw data after the JPEG
            byte[] big = jpegOf(2_000_000, 82);
            byte[] smallS = jpegOf(150_000, 83);
            FakeFujiCamera.Obj r = new FakeFujiCamera.Obj("DSCF0081.RAF", 0xB103, rafData, rafData.length);
            FakeFujiCamera.Obj j = new FakeFujiCamera.Obj("DSCF0082.JPG", 0x3801, big, big.length);
            j.small = smallS;
            FakeFujiCamera.Obj n = new FakeFujiCamera.Obj("DSCF0083.JPG", 0x3801, big, big.length); // no S copy for it
            cam.objects.add(r);
            cam.objects.add(j);
            cam.objects.add(n);
            TransferEngine eng = engine(cam, new FujiWifi.Options(), new Notes());
            List<ObjectInfo> list = eng.listObjects(null);
            ObjectInfo ir = named(list, "DSCF0081.RAF");
            ObjectInfo ij = named(list, "DSCF0082.JPG");
            ObjectInfo in = named(list, "DSCF0083.JPG");

            long sent = cam.dataBytesSent.get();
            byte[] p = eng.rafPreview(ir, null);
            check("RAF preview is the embedded JPEG", p != null && Arrays.equals(p, inner));
            long used = cam.dataBytesSent.get() - sent;
            check("only header + JPEG read (" + used + " of " + rafData.length + " B)", used == 512 + inner.length);
            check("gallery mode back after the RAF preview", "1016 D226=0".equals(lastSet(cam, "D226")));
            check("not a RAF -> null", eng.rafPreview(ij, null) == null);

            byte[] s = eng.smallCopy(ij, null);
            check("size-S copy from the camera", s != null && Arrays.equals(s, smallS));
            check("D22E = 1 (size S) then D226 = 1, like XApp", indexOfEntry(cam.log, "1016 D22E=1") >= 0
                    && indexOfEntry(cam.log, "1016 D226=1") > indexOfEntry(cam.log, "1016 D22E=1"));
            check("gallery mode back after the S copy", "1016 D226=0".equals(lastSet(cam, "D226")));
            check("no whole JPEG in the copy -> null (fall back to the original)", eng.smallCopy(in, null) == null);
            check("gallery mode back after a failed copy", "1016 D226=0".equals(lastSet(cam, "D226")));

            int logBefore = cam.log.size();
            File g = eng.download(ij, tmp(), listener(new AtomicInteger()));
            check("a download after the previews gets the original", sha(g).equals(sha(big)));
            int sets;
            synchronized (cam.log) {
                sets = (int) cam.log.subList(logBefore, cam.log.size()).stream().filter("1016 D226=2"::equals).count();
            }
            check("one 0xD226 = 2 per file, not a second one after the transfer (" + sets + ")", sets == 1);
            check("after the download the camera is still in original-file mode", "1016 D226=2".equals(lastSet(cam, "D226")));
            eng.galleryMode();
            check("galleryMode() puts the gallery mode back, like XApp after an import", "1016 D226=0".equals(lastSet(cam, "D226")));
            eng.close();

            int[] side = cam.openSidePorts();
            FujiWifi.Options fo = new FujiWifi.Options();
            fo.bluetoothHandoff = false; // Wi-Fi from the camera menu: classic mode
            fo.eventPort = side[0];
            fo.liveViewPort = side[1];
            TransferEngine classic = engine(cam, fo, new Notes());
            List<ObjectInfo> l2 = classic.listObjects(null);
            check("no S copies outside XApp mode", classic.smallCopy(named(l2, "DSCF0082.JPG"), null) == null);
            classic.close();
        } finally {
            PtpSession.wire = null;
        }
        boolean modes = false, timed = false;
        synchronized (wire) {
            for (String l : wire) {
                if (l.startsWith("1016 D22E=1 ") && l.contains("-> OK")) modes = true;
                if (l.startsWith("101B ") && l.matches(".* in \\d+ ms, \\d+ B, dati dopo \\d+ ms")) timed = true;
            }
        }
        check("wire trace: property values sent (" + wire.size() + " lines)", modes);
        check("wire trace: time, bytes and wait before the data of each GetPartialObject", timed);
    }

    static ObjectInfo named(List<ObjectInfo> list, String name) {
        for (ObjectInfo i : list) if (i.filename.equals(name)) return i;
        throw new IllegalStateException(name + " not listed");
    }

    /** n bytes of pattern framed as a JPEG: SOI at the start, EOI at the end. */
    static byte[] jpegOf(int n, int seed) {
        byte[] d = FakeFujiCamera.pattern(n, seed);
        d[0] = (byte) 0xFF;
        d[1] = (byte) 0xD8;
        d[n - 2] = (byte) 0xFF;
        d[n - 1] = (byte) 0xD9;
        for (int i = 2; i < n - 2; i++) if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD9) d[i + 1] = 0;
        return d;
    }

    /** Last SetDevicePropValue of that property, e.g. "1016 D226=0". */
    static String lastSet(FakeFujiCamera cam, String code) {
        String r = null;
        synchronized (cam.log) {
            for (String e : cam.log) if (e.startsWith("1016 " + code + "=")) r = e;
        }
        return r;
    }

    static void queueDroppingCamera() throws Exception {
        System.out.println("== camera that answers only the last of several queued requests: no reconnect needed");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            for (int i = 1; i <= 13; i++) cam.objects.add(new FakeFujiCamera.Obj(String.format("DSCF%04d.JPG", i), 0x3801, new byte[50 + i], 50 + i));
            cam.dropQueued = true;
            TransferEngine eng = engine(cam);
            AtomicInteger retries = new AtomicInteger();
            List<ObjectInfo> list = eng.listObjects(listener(retries));
            boolean right = list.size() == 13;
            for (ObjectInfo i : list) right &= i.filename.equals(cam.objects.get((int) i.handle - 1).name);
            check("all 13 listed on the same session, without a retry", right && retries.get() == 0);
            check("the refused queue is reported, to remember it for this camera", eng.listingRefusedQueue());
            eng.close();

            // next connection: the app remembered it, so nothing is queued at all
            int queuedBefore = cam.queuedCommandsSeen.get();
            FujiTcpLink.Options lo = new FujiTcpLink.Options();
            lo.readTimeoutMs = 1500;
            lo.connectTimeoutMs = 1000;
            TransferEngine.Options eo = new TransferEngine.Options();
            eo.listOneAtATime = true;
            TransferEngine one = new TransferEngine(() -> FujiTcpLink.openSession(SocketFactory.getDefault(), "127.0.0.1",
                    cam.port(), GUID, "selftest-phone", lo), eo);
            check("listOneAtATime: all 13 listed, no request queued",
                    one.listObjects(null).size() == 13 && cam.queuedCommandsSeen.get() == queuedBefore);
            check("listOneAtATime: nothing new to remember", !one.listingRefusedQueue());
            one.close();
        }
    }

    static void thumbnailsDuringListing() throws Exception {
        System.out.println("== thumbnails and a download served between listing groups, not after the whole card");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] last = FakeFujiCamera.pattern(300_000, 99);
            for (int i = 1; i <= 80; i++) {
                String n = String.format("DSCF%04d.JPG", i);
                byte[] d = i == 80 ? last : new byte[60 + i];
                cam.objects.add(new FakeFujiCamera.Obj(n, 0x3801, d, d.length));
                cam.thumbs.put(n, FakeFujiCamera.pattern(2000 + i, i));
            }
            TransferEngine eng = engine(cam);
            final TransferEngine e = eng;
            final List<ObjectInfo>[] seen = new List[1];
            final byte[][] thumb = new byte[1][];
            final File[] file = new File[1];
            final int[] doneAtThumb = {-1};
            final int[] doneNow = {0};
            final File dir = tmp();
            eng.listObjects(null, null, new TransferEngine.ListingListener() {
                public void onPartial(List<ObjectInfo> soFar, int done, int total) {
                    seen[0] = soFar;
                    doneNow[0] = done;
                }

                public boolean onIdle() throws IOException {
                    if (thumb[0] != null || seen[0] == null || seen[0].isEmpty()) return false;
                    ObjectInfo newest = null;
                    for (ObjectInfo i : seen[0]) if (newest == null || i.handle > newest.handle) newest = i;
                    thumb[0] = e.thumbnail(newest, true);
                    file[0] = e.download(newest, dir, null);
                    doneAtThumb[0] = doneNow[0];
                    return true;
                }
            });
            check("thumbnail of the newest photo fetched while the listing was at " + doneAtThumb[0] + "/80",
                    thumb[0] != null && Arrays.equals(thumb[0], cam.thumbs.get("DSCF0080.JPG")) && doneAtThumb[0] < 80);
            check("download in between is the right file", file[0] != null && sha(file[0]).equals(sha(last)));
            check("no thumbnail request without its ObjectInfo", cam.thumbsWithoutInfo.get() == 0);
            eng.close();
        }
    }

    static void genericPtpIsIgnored() throws Exception {
        System.out.println("== why it failed before: OpenSession + generic PTP gets no answer from the X-M5");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, new byte[1000], 1000));
            FujiTcpLink.Options lo = new FujiTcpLink.Options();
            lo.readTimeoutMs = 800;
            FujiTcpLink link = FujiTcpLink.connect(SocketFactory.getDefault(), "127.0.0.1", cam.port(),
                    FujiTcpLink.FUJI_CLIENT_GUID, "phone", lo);
            PtpSession s = new PtpSession(link);
            s.openSession();
            boolean failed = throwsIo(() -> ObjectLister.listAll(s));
            check("listing without the set-up gets no answer", failed && cam.ignoredBeforeSetup.get() > 0);
            s.close();
        }
    }

    static void slowClientStateAnswer() throws Exception {
        System.out.println("== camera answers the function change after 4 s (dialog on the body)");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, new byte[1000], 1000));
            cam.clientStateDelayMs = 4000;
            Notes notes = new Notes();
            TransferEngine eng = engine(cam, new FujiWifi.Options(), notes); // read timeout 1.5 s
            check("session opened despite a reply slower than the read timeout", eng.listObjects(null).size() == 1);
            check("one socket, no reconnect", cam.connections.get() == 1);
            check("slow-answer note shown once", notes.slow.get() == 1);
            eng.close();
        }
    }

    static void classicWifiFromCameraMenu() throws Exception {
        System.out.println("== Wi-Fi started from the camera menu (no Bluetooth): fudge's classic remote set-up");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            byte[] a = FakeFujiCamera.pattern(2 * 1024 * 1024 + 5, 71);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, a, a.length));
            int[] side = cam.openSidePorts();
            FujiWifi.Options fo = new FujiWifi.Options();
            fo.bluetoothHandoff = false;
            fo.eventPort = side[0];
            fo.liveViewPort = side[1];
            TransferEngine eng = engine(cam, fo, new Notes());
            List<ObjectInfo> list = eng.listObjects(null);
            FujiWifi f = eng.session().fujiWifi();
            check("classic mode", f != null && !f.isXapp());
            check("ClientState 5 (remote), DF24 = 0x2000C", logged(cam, "1016 DF01=5") && logged(cam, "1016 DF24=131084"));
            int open = indexOfEntry(cam.log, "101C at ");
            String openEntry = open >= 0 ? cam.log.get(open) : "";
            check("capture opened and closed with its own transaction id",
                    open >= 0 && logged(cam, "1018 tid=" + openEntry.substring(8)));
            check("both extra ports connected", cam.sidePortConnections.get() == 2);
            check("then image view: DF00 = 6, DF01 = 11, DF25 = 5", logged(cam, "1016 DF00=6")
                    && logged(cam, "1016 DF01=11") && logged(cam, "1016 DF25=5"));
            check("real size via D227 = 1", list.get(0).size == a.length);
            File out = eng.download(list.get(0), tmp(), null);
            check("download matches, original file", sha(out).equals(sha(a)) && cam.downloadsNotOriginal.get() == 0);
            eng.close();
        }
    }

    static void olderBodyAfterBluetooth() throws Exception {
        System.out.println("== older body (remote version 0x20004) after Bluetooth: no XApp mode, like fudge's shim");
        try (FakeFujiCamera cam = new FakeFujiCamera()) {
            cam.remoteVersion = 0x20004L;
            cam.cameraStateAfterAccess = 2; // full access
            byte[] a = FakeFujiCamera.pattern(1024 * 1024 + 3, 81);
            cam.objects.add(new FakeFujiCamera.Obj("DSCF0001.JPG", 0x3801, a, a.length));
            TransferEngine eng = engine(cam, new FujiWifi.Options(), new Notes());
            List<ObjectInfo> list = eng.listObjects(null);
            check("classic mode, ClientState 5", !eng.session().fujiWifi().isXapp() && logged(cam, "1016 DF01=5"));
            check("no capture without remote access state", cam.openCaptures.get() == 0);
            check("download matches", sha(eng.download(list.get(0), tmp(), null)).equals(sha(a)));
            eng.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    static TransferEngine plainEngine(FakeFujiCamera cam) {
        FujiTcpLink.Options lo = linkOptions();
        return new TransferEngine(() -> {
            FujiTcpLink link = FujiTcpLink.connect(SocketFactory.getDefault(), "127.0.0.1", cam.port(), GUID,
                    "selftest-phone", lo);
            PtpSession s = new PtpSession(link);
            s.openSession();
            return s;
        }, engineOptions());
    }

    static FujiTcpLink.Options linkOptions() {
        FujiTcpLink.Options lo = new FujiTcpLink.Options();
        lo.readTimeoutMs = 1500;
        lo.connectTimeoutMs = 1000;
        lo.pairingWaitMs = 5000;
        lo.initRetryDelayMs = 50;
        return lo;
    }

    static TransferEngine.Options engineOptions() {
        TransferEngine.Options eo = new TransferEngine.Options();
        eo.backoffBaseMs = 50;
        eo.backoffMaxMs = 200;
        return eo;
    }

    static TransferEngine engine(FakeFujiCamera cam, FujiWifi.Options fo, FujiWifi.Progress pr) {
        FujiTcpLink.Options lo = linkOptions();
        return new TransferEngine(() -> FujiTcpLink.openSession(SocketFactory.getDefault(), "127.0.0.1",
                cam.port(), GUID, "selftest-phone", lo, fo, pr), engineOptions());
    }

    static TransferEngine engine(FakeFujiCamera cam) {
        FujiTcpLink.Options lo = new FujiTcpLink.Options();
        lo.readTimeoutMs = 1500;
        lo.connectTimeoutMs = 1000;
        lo.pairingWaitMs = 5000;
        lo.initRetryDelayMs = 50;
        TransferEngine.Options eo = new TransferEngine.Options();
        eo.backoffBaseMs = 50;
        eo.backoffMaxMs = 200;
        return new TransferEngine(() -> FujiTcpLink.openSession(SocketFactory.getDefault(), "127.0.0.1",
                cam.port(), GUID, "selftest-phone", lo), eo);
    }

    static TransferEngine.Listener listener(AtomicInteger retries) {
        return new TransferEngine.Listener() {
            public void onProgress(ObjectInfo i, long d, long t) {
            }

            public void onRetry(ObjectInfo i, int attempt, Exception c) {
                retries.incrementAndGet();
                System.out.println("   retry #" + attempt + " after: " + c.getMessage());
            }

            public boolean isCancelled() {
                return false;
            }
        };
    }

    static File tmp() throws IOException {
        File d = Files.createTempDirectory("fujixfer-test").toFile();
        d.deleteOnExit();
        return d;
    }

    static String sha(File f) throws Exception {
        return sha(Files.readAllBytes(f.toPath()));
    }

    static String sha(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ Immich

    /** A file of known bytes as an Immich source. */
    static ImmichClient.Source bytesSource(byte[] b) {
        return new ImmichClient.Source() {
            public java.io.InputStream open() {
                return new java.io.ByteArrayInputStream(b);
            }

            public long length() {
                return b.length;
            }
        };
    }

    static String sha1(byte[] b) throws Exception {
        return ImmichClient.sha1Hex(new java.io.ByteArrayInputStream(b));
    }

    static void immichSync() throws Exception {
        System.out.println("== Immich: duplicates are checked by SHA-1 and not uploaded");
        java.util.Set<String> known = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.List<byte[]> received = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<String> fields = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger failNext = new AtomicInteger(); // HTTP code to answer once on the next upload
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api", ex -> {
            String path = ex.getRequestURI().getPath();
            boolean keyOk = "secret".equals(ex.getRequestHeaders().getFirst("x-api-key"))
                    || "Bearer tok123".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    || path.equals("/api/auth/login") || path.equals("/api/server/version");
            byte[] req = ex.getRequestBody().readAllBytes();
            String out;
            int code = 200;
            if (!keyOk) {
                out = "{\"message\":\"Invalid API key\"}";
                code = 401;
            } else if (path.equals("/api/auth/login")) {
                String body = new String(req, java.nio.charset.StandardCharsets.UTF_8);
                if (body.contains("\"email\":\"jerry@example.com\"") && body.contains("\"password\":\"p\\\"w\"")) {
                    out = "{\"accessToken\":\"tok123\",\"userId\":\"u1\"}";
                    code = 201;
                } else {
                    out = "{\"message\":\"Incorrect email or password\"}";
                    code = 401;
                }
            } else if (path.equals("/api/server/version")) {
                out = "{\"major\":1,\"minor\":118,\"patch\":2}";
            } else if (path.equals("/api/assets/statistics")) {
                out = "{\"images\":12000,\"videos\":480,\"total\":12480}";
            } else if (path.equals("/api/users/me")) {
                out = "{\"email\":\"jerry@example.com\"}";
            } else if (path.equals("/api/assets/bulk-upload-check")) {
                checks.incrementAndGet();
                String body = new String(req, java.nio.charset.StandardCharsets.UTF_8);
                String sum = body.replaceAll(".*\"checksum\":\"([0-9a-f]+)\".*", "$1");
                out = known.contains(sum)
                        ? "{\"results\":[{\"id\":\"x\",\"action\":\"reject\",\"reason\":\"duplicate\",\"assetId\":\"a1\"}]}"
                        : "{\"results\":[{\"id\":\"x\",\"action\":\"accept\"}]}";
            } else if (path.equals("/api/assets")) {
                int f = failNext.getAndSet(0);
                if (f != 0) {
                    out = "{}";
                    code = f;
                } else {
                    String text = new String(req, java.nio.charset.StandardCharsets.ISO_8859_1);
                    String bound = ex.getRequestHeaders().getFirst("Content-Type").replaceAll(".*boundary=", "");
                    int i = text.indexOf("name=\"assetData\"");
                    int start = text.indexOf("\r\n\r\n", i) + 4;
                    int end = text.lastIndexOf("\r\n--" + bound + "--");
                    byte[] file = java.util.Arrays.copyOfRange(req, start, end);
                    received.add(file);
                    fields.add(text.substring(0, i));
                    known.add(ImmichClient.sha1Hex(new java.io.ByteArrayInputStream(file)));
                    out = "{\"id\":\"n1\",\"status\":\"created\"}";
                    code = 201;
                }
            } else {
                out = "{}";
                code = 404;
            }
            byte[] o = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, o.length);
            ex.getResponseBody().write(o);
            ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            byte[] a = new byte[300_000];
            new java.util.Random(1).nextBytes(a);
            byte[] b = new byte[1000];
            new java.util.Random(2).nextBytes(b);
            long t = 1_793_000_000_000L;

            ImmichClient c = new ImmichClient(base + "/", "secret", "fuji-test");
            check("normalize adds /api and a scheme", ImmichClient.normalize("host:2283/").equals("http://host:2283/api")
                    && ImmichClient.normalize("https://h/api/").equals("https://h/api"));
            check("test() returns the account", c.test().equals("jerry@example.com"));

            check("a new file is uploaded", c.sync("DSCF0001.JPG", bytesSource(a), t, t) == ImmichClient.Outcome.UPLOADED);
            check("the server got the exact bytes", received.size() == 1 && java.util.Arrays.equals(received.get(0), a));
            check("device id, dates and name were sent", fields.get(0).contains("fuji-test")
                    && fields.get(0).contains("2026-10-26T") && fields.get(0).contains("deviceAssetId"));

            check("the same bytes again are a duplicate", c.sync("DSCF0001.JPG", bytesSource(a), t, t) == ImmichClient.Outcome.DUPLICATE);
            check("the same bytes under another name too (XApp copy, renamed file)",
                    c.sync("IMG_9999.JPG", bytesSource(a), t, t) == ImmichClient.Outcome.DUPLICATE);
            check("a duplicate sends no file", received.size() == 1 && checks.get() == 3);

            long[] seen = {0, 0};
            c.progress = (done, total) -> { seen[0] = done; seen[1] = total; };
            check("another file is uploaded", c.sync("DSCF0002.JPG", bytesSource(b), t, t) == ImmichClient.Outcome.UPLOADED
                    && received.size() == 2 && java.util.Arrays.equals(received.get(1), b));
            check("upload progress reaches the whole file", seen[0] == b.length && seen[1] == b.length);
            c.progress = null;

            check("server version", c.version().equals("1.118.2"));
            check("photos and videos of the account", c.assetCount() == 12480);
            String tok = ImmichClient.login(base, "jerry@example.com", "p\"w");
            check("login with email and password gives a token", tok.equals("tok123"));
            ImmichClient byLogin = new ImmichClient(base, tok, "fuji-test", true);
            check("the token works like a key (Bearer)", byLogin.test().equals("jerry@example.com")
                    && byLogin.sync("DSCF0001.JPG", bytesSource(a), t, t) == ImmichClient.Outcome.DUPLICATE);
            try {
                ImmichClient.login(base, "jerry@example.com", "nope");
                check("wrong password fails", false);
            } catch (ImmichClient.ImmichException e) {
                check("wrong password: permanent, said plainly", e.permanent && e.getMessage().contains("password"));
            }

            ImmichClient bad = new ImmichClient(base, "wrong", "fuji-test");
            try {
                bad.sync("DSCF0003.JPG", bytesSource(b), t, t);
                check("wrong key fails", false);
            } catch (ImmichClient.ImmichException e) {
                check("wrong key: permanent error, no retry", e.permanent && e.code == 401);
            }

            byte[] d = new byte[500];
            new java.util.Random(3).nextBytes(d);
            failNext.set(503);
            try {
                c.sync("DSCF0004.JPG", bytesSource(d), t, t);
                check("a 503 fails", false);
            } catch (ImmichClient.ImmichException e) {
                check("503 on upload: temporary error (try later)", !e.permanent && e.code == 503);
            }
            check("and the retry then goes through", c.sync("DSCF0004.JPG", bytesSource(d), t, t) == ImmichClient.Outcome.UPLOADED);
        } finally {
            srv.stop(0);
        }
        ImmichClient down = new ImmichClient("http://127.0.0.1:1", "secret", "fuji-test");
        down.connectTimeoutMs = 500;
        try {
            down.test();
            check("server down fails", false);
        } catch (java.io.IOException e) {
            check("server unreachable is an IOException, not a permanent refusal",
                    !(e instanceof ImmichClient.ImmichException));
        }
    }

    static void check(String what, boolean ok) {
        System.out.println("   [" + (ok ? "ok" : "FAIL") + "] " + what);
        if (!ok) failures++;
    }
}
