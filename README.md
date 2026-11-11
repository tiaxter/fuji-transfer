# Fuji Transfer

Android app to download photos from a Fujifilm X/GFX camera, over Wi-Fi or a USB cable, with automatic
reconnection and resuming of interrupted downloads.

## Structure

- `core/` – protocol and transfer logic, plain Java with no dependencies.
  - `FujiTcpLink`: Fuji Wi-Fi transport (TCP 55740, 82-byte handshake like XApp: fixed client id + the phone's IP).
  - `FujiWifi`: Wi-Fi session setup like XApp, with its workarounds (waiting for access, ClientState 20,
    gallery configuration, handles 1..N, real sizes). Without this step the camera does not answer.
  - `PacketLink`: adapter from USB bulk packets to a byte stream.
  - `PtpSession`: PTP session (OpenSession, ObjectInfo, GetPartialObject, ...), the same for Wi-Fi and USB.
  - `FujiBle`: XApp's Bluetooth LE protocol (pairing, reconnection, asking the camera to turn on its Wi-Fi),
    independent of Android: it runs on top of the `GattLink` interface. Details in `docs/BLUETOOTH.md`.
  - `TransferEngine`: downloads in 2 MiB chunks to a `.part` file, retries with backoff, resumes from the bytes
    already written, finds the object again if the handles change after a reconnection, falls back to GetObject
    when GetPartialObject is missing.
- `app/` – Android app (Kotlin, Jetpack Compose; MapLibre for the map, Media3 and NextLib for video).
  - `TransferService`: foreground service with WakeLock/WifiLock; for Wi-Fi it requests the camera's network with
    `WifiNetworkSpecifier` and binds its sockets to that network, so Android does not drop it as "no internet".
  - `BleScanner`, `BleGattLink`: finding the camera, and blocking GATT for `FujiBle`.
  - `UsbCamera`: PTP over USB bulk (Still Image class interface).
  - `Thumbs`: thumbnail cache (memory + files), requested by the grid and fetched by the service between downloads.
  - `MainActivity`: connection, thumbnail grid, filters, selection, progress.
  - Saving: by default photos (JPG/HEIF), RAF and videos all go into `Pictures/Fujifilm`. Settings › Save folder picks the base (`Pictures`, `DCIM`, `Download`, or one folder per type: `Pictures`/`Movies`/`Download`) and the folder name; a file Android refuses in the chosen folder falls back to the folder for its type.

## Build

```
./gradlew :core:selfTest        # protocol tests against a simulated Fuji camera (needs only JDK 17+)
./gradlew :app:assembleDebug    # APK in app/build/outputs/apk/debug/
```

Without installing anything (needs only Docker):

```
./docker-build.sh          # protocol tests + debug APK in dist/
```

The first run downloads the Android SDK into the image; after that the Gradle cache stays in the
`fuji-gradle-cache` volume. On Apple Silicon Macs the image runs under x86-64 emulation, so it is slower.

Or open the folder in Android Studio. Requires Android 10+ (minSdk 29).

## Usage

1. Wi-Fi (cameras with Bluetooth):
   - if the camera is already paired with this phone through XApp: turn it on and tap "Connect". The app reuses
     the same pairing;
   - if it has never been paired: on the camera start "Pairing registration" in the Bluetooth settings, then
     "Pair new camera" in the app;
   - Android asks to confirm the camera's Wi-Fi network.
   - The log button at the top shows the technical log (search, services, every Bluetooth exchange): "Copy" to send it.
2. Manual Wi-Fi: if you turned the Wi-Fi on from the camera's menu, open "Manual connection" and enter the network.
3. USB: plug in the cable, set the camera to a PTP mode (not "card reader"), then "Connect via USB".
4. Thumbnails appear in the grid as they arrive. Tap to select, touch and hold to see a photo bigger.

## Translations

The app is in English and Italian. All the text people see is in Android string resources:

- `app/src/main/res/values/strings.xml`: English, the source language;
- `app/src/main/res/values-it/strings.xml`: Italian.

These files can be translated with Weblate or any tool that reads Android string resources. In Weblate, a component
with file format "Android String Resource", file mask `app/src/main/res/values-*/strings.xml` and monolingual base
language file `app/src/main/res/values/strings.xml`. Counts use `<plurals>`, placeholders are numbered (`%1$s`,
`%2$d`), and `app_name` is not translatable.

A new language is a new `values-<code>/strings.xml`: nothing else to change. The list of languages Android 13+ offers
in the app's language settings is generated from the `values-*` folders at build time (`generateLocaleConfig`;
`app/src/main/res/resources.properties` says the folder without a qualifier is English).

What is not translated: the technical log's own lines (the "Log" screen and the shared log file) stay in Italian, as
do a few messages from the protocol code in `core/`, which can show up as the technical detail of a connection error.

## Verification status

Verified (automated tests in `core`, simulated camera): 82-byte handshake, delayed pairing, connection reset,
silent stall, resume, renumbered handles, files over 4 GiB, USB packet reassembly, thumbnails (0x9055 with
fallback to GetThumb, after reconnection), Bluetooth sequences for old and RED firmware, turning the Wi-Fi on with the
camera busy, without notifications, with a refusal and with a timeout.

The app's Kotlin code was checked with the Kotlin 2.0.21 compiler against an API 35 `android.jar` built from AOSP
(not the SDK's official one) and a fake `R` class: no errors. The XML resources were not compiled (that needs
aapt2, so the Docker build).

Comparison with the original APK: ports, handshake, operation codes and Bluetooth protocol read from the code of
XApp 2.7.6 (see `docs/ANALISI_XAPP.md` and `docs/BLUETOOTH.md`).

Not verified: nothing has been tried on a real camera, over Bluetooth or over Wi-Fi.
