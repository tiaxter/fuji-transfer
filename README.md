# Fuji Transfer

App Android per scaricare le foto da una camera Fujifilm X/GFX, via Wi-Fi o via cavo USB, con
riconnessione automatica e ripresa dei download interrotti.

## Struttura

- `core/` – protocollo e logica di trasferimento, Java puro senza dipendenze.
  - `FujiTcpLink`: trasporto Wi-Fi Fuji (TCP 55740, handshake da 82 byte come XApp: id client fisso + IP del telefono).
  - `FujiWifi`: impostazione della sessione Wi-Fi come XApp e fudge (attesa dell'accesso, ClientState 20,
    configurazione galleria, handle 1..N, dimensioni reali). Senza questo passo la camera non risponde.
  - `PacketLink`: adattatore da pacchetti bulk USB a flusso di byte.
  - `PtpSession`: sessione PTP (OpenSession, ObjectInfo, GetPartialObject, ...), identica per Wi-Fi e USB.
  - `FujiBle`: protocollo Bluetooth LE di XApp (abbinamento, riconnessione, richiesta di accensione del Wi-Fi),
    indipendente da Android: gira sopra l'interfaccia `GattLink`. Dettagli in `docs/BLUETOOTH.md`.
  - `TransferEngine`: download a blocchi da 2 MiB su file `.part`, retry con backoff, ripresa dai byte già scritti,
    ri-aggancio dell'oggetto se gli handle cambiano dopo la riconnessione, fallback a GetObject se manca GetPartialObject.
- `app/` – app Android (Kotlin, nessuna dipendenza oltre a `core`).
  - `TransferService`: servizio in foreground con WakeLock/WifiLock; per il Wi-Fi richiede la rete della camera con
    `WifiNetworkSpecifier` e lega i socket a quella rete, così Android non la scarta perché "senza internet".
  - `BleScanner`, `BleGattLink`: ricerca della camera e GATT bloccante per `FujiBle`.
  - `UsbCamera`: PTP su USB bulk (interfaccia classe Still Image).
  - `Thumbs`: cache delle miniature (memoria + file), richieste dalla griglia e scaricate dal servizio tra un download e l'altro.
  - `MainActivity`: connessione, griglia con miniature, filtri, selezione, avanzamento.
  - Salvataggio: JPG/HEIF in `Pictures/Fujifilm`, video in `Movies/Fujifilm`, RAF e altro in `Download/Fujifilm`.

## Build

```
./gradlew :core:selfTest        # test del protocollo contro una camera Fuji simulata (serve solo JDK 17+)
./gradlew :app:assembleDebug    # APK in app/build/outputs/apk/debug/
```

Senza installare nulla (serve solo Docker):

```
./docker-build.sh          # test del protocollo + APK debug in dist/
```

La prima volta scarica l'SDK Android dentro l'immagine; poi la cache di Gradle resta nel volume `fuji-gradle-cache`.
Su Mac Apple Silicon l'immagine gira in emulazione x86-64, quindi è più lenta.

Se la rete aziendale intercetta il TLS (errore `self-signed certificate in certificate chain`), lo script prova da solo
a salvare la CA in `docker/certs/`. Puoi anche metterci a mano i file `.crt` della tua azienda e rilanciare.

Oppure apri la cartella in Android Studio. Richiede Android 10+ (minSdk 29).

## Uso

1. Wi-Fi (camere con Bluetooth):
   - se la camera è già abbinata al telefono con XApp: accendila e tocca "Connetti". L'app riusa lo stesso abbinamento;
   - se non è mai stata abbinata: sulla camera avvia "Registrazione abbinamento" nelle impostazioni Bluetooth, poi
     "Abbina nuova camera" nell'app;
   - Android chiede di confermare la rete Wi-Fi della camera.
   - "Registro" in alto mostra il registro tecnico (ricerca, servizi, ogni scambio Bluetooth): "Copia" per mandarlo.
2. Wi-Fi manuale: se il Wi-Fi l'hai acceso dal menu della camera, apri "Connessione manuale" e inserisci la rete.
3. USB: collega il cavo, imposta la camera in una modalità PTP (non "card reader"), poi "Connetti via USB".
4. Le miniature compaiono nella griglia man mano. Tocca per selezionare, tieni premuto per vederla più grande.

## Stato della verifica

Verificato (test automatici in `core`, camera simulata): handshake a 82 byte, pairing ritardato, reset di connessione,
stallo silenzioso, ripresa, handle rinumerati, file oltre 4 GiB, riassemblaggio pacchetti USB, miniature (0x9055 con
ripiego su GetThumb, dopo riconnessione), sequenze Bluetooth per firmware vecchi e RED, accensione del Wi-Fi con camera
occupata, senza notifiche, con rifiuto e con timeout.

Il codice Kotlin dell'app è stato controllato con il compilatore Kotlin 2.0.21 contro un `android.jar` API 35 compilato
da AOSP (non quello ufficiale dell'SDK) e una classe `R` finta: nessun errore. Le risorse XML non sono state compilate
(serve aapt2, quindi il build Docker).

Confronto con l'APK originale: porte, handshake, codici delle operazioni e protocollo Bluetooth letti dal codice di XApp
2.7.6 (vedi `docs/ANALISI_XAPP.md` e `docs/BLUETOOTH.md`).

Non verificato: nulla è stato provato su una camera reale, né via Bluetooth né via Wi-Fi.
