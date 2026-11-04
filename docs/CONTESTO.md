# Fuji Transfer — contesto per nuove chat

App Android per scaricare foto e video da una Fujifilm X-M5 via Bluetooth → Wi-Fi (come XApp) o via USB.
Telefono di prova: OPPO PME110, Android 16 (API 36). Interfaccia e log in italiano.

## Dove sta il codice
- Sul Mac: `~/Projects/fuji-transfer` (la chat deve essere collegata al computer).
- Build: `./docker-build.sh` (test del protocollo + APK debug in `dist/`). Sul Mac non c'è Android SDK: si compila solo in Docker.
- `core/` — Java puro, senza dipendenze: protocollo PTP/IP Fuji, BLE, elenco, download, EXIF. Test: `core/src/test/.../SelfTest.java` con `FakeFujiCamera` (devono dare ALL CHECKS PASSED).
- `app/` — Kotlin, `com.fujixfer.app`. Unica libreria esterna: MapLibre 13.6.1 (mappe OpenFreeMap "liberty", senza chiave). Kotlin plugin 2.2.10, `compilerOptions { jvmTarget = JVM_17 }`.
- `app/debug.keystore` — chiave di debug fissa (il container Docker viene buttato a ogni build).

## Regole da rispettare
- Non rifare l'abbinamento se XApp ha già abbinato la camera: riusarlo.
- Messaggi di log accurati: niente ipotesi presentate come fatti, niente consigli falsi.
- Niente marchi o loghi Fujifilm nelle icone.
- Installare le build nuove sopra la vecchia (disinstallare perde abbinamento, cache elenco, miniature, metadati, segni "scaricato" e l'approvazione Wi-Fi).
- Output di disassemblaggio (APK XApp) tenuto piccolo.
- Modifiche ai file sul Mac: dopo ogni copia verificare l'md5 (in passato una copia è fallita in silenzio).

## Protocollo: cosa sappiamo (verificato sulla X-M5)
- Connessione come XApp: BLE (riuso abbinamento RED) → richiesta Wi-Fi (InCameraViewing) → SSID/password/BSSID via BLE → `WifiNetworkSpecifier` con SSID letterale + BSSID + WPA2, senza capability INTERNET (`requestNetwork`). Android ricorda l'approvazione per quel solo access point.
- PTP/IP su 192.168.0.1:55740, GUID fisso, modo XApp = ClientState 20.
- La X-M5 non accoda richieste: con più comandi in volo risponde solo all'ultimo (`PipelineRefused`) → elenco un comando alla volta.
- 0xD226 = 0 modo galleria (dimensioni segnaposto, leggero); = 2 file originali (dimensioni vere, ~5× più pesante). Elenco in modo galleria; dimensione vera chiesta solo prima del download.
- GetPartialObject può restituire meno del richiesto (1 MiB − 32 byte sui MOV): un pezzo corto non è un errore, solo uno vuoto.
- 0x9053 (offset 0, count 30000): u32 giorni; per giorno u32 (ignorato), stringa PTP data "yyyyMMdd", u32 numero file; dal più recente. 0xD621: u32 n + handle in ordine di galleria. XApp divide la lista 0xD621 in giorni con i conteggi 0x9053 (qualche file finisce nel giorno vicino: tollerato).
- EXIF: GPS IFD, MakerNote Fuji 0x1401 FilmMode, 0x1003 Saturation (bianco e nero/Acros). RAF: offset/lunghezza del JPEG interno big-endian ai byte 84/88. Letti 64 KiB dall'inizio.

## Problema aperto: cadute della connessione
- Tipo A: alla prima connessione cade a 18,1 s esatti da quando il telefono si collega al Wi-Fi della camera ("Software caused connection abort", lato telefono; la rete risulta ancora su; la camera poi rifiuta e spegne il Wi-Fi dopo ~9 s). Dopo la riconnessione non si ripete. Causa ignota. Nuovo indizio (log del 3 nov, 16:41 e 16:53, entrambe a 18,06–18,07 s): in quell'istante il telefono toglie l'indirizzo IP alla rete della camera e lo riassegna 40 ms dopo (`indirizzi nessuno` → `192.168.0.129`), poi la camera rifiuta la riconnessione (Connection reset) e spegne il Wi-Fi. Ipotesi DHCP smentita (log 23:13: lease 28800 s). Stesso log: caduta a 18,0 s anche senza toccare l'app (solo lettura posizioni in background): "Software caused connection abort" 19 ms prima che Android tolga e rimetta l'indirizzo; la camera spegne il Wi-Fi ~10 s dopo anche senza tentativi di riconnessione. Il registro su file ora ha anche gli eventi Wi-Fi di Android (stato rete, supplicant) per capire se a 18 s il telefono si riassocia o rifà solo l'IP. Log del 4 nov (13:44, 13:57): nessuna caduta, ma in ogni sessione il collegamento rallenta proprio attorno a w+18 (pezzi da 1 MiB da 0,3 s che diventano 0,9–1,1 s, una lettura da 64 KiB di 4,3 s); le tre cadute sono la versione grave dello stesso evento. Esperimento in corso: il lavoro in background si ferma da w+15,5 a w+21 (download chiesti esclusi). Velocità misurata in Wi-Fi a 2,4 GHz (2412/2437 MHz): ~4 MB/s per un JPG, 2,0 MB/s per un MOV. Disconnessione: ora CloseSession prima di lasciare la rete; riconnessione Wi-Fi: 3 tentativi a 3 s di distanza (13:59 la camera rifiutava l'associazione subito dopo una disconnessione senza CloseSession). Il log ora riporta il lease DHCP e l'età della rete al cambio di indirizzi. Non dipende dalle anteprime: con il loro ritmo nuovo (0,7 s tra due, 2,5 s dopo tre) la seconda connessione regge.
- Tipo B: la camera risponde sempre più lenta (0,13 → 0,5 → 3 → 8 s per GetObjectInfo, soprattutto RAF vecchi) e poi spegne il Wi-Fi (notifica BLE A68E3F66 = 0x8000). Mitigazione: pausa automatica in `ObjectLister.Pace`, lettura EXIF a blocchi piccoli con pause.
- Il log ora registra: onLost/onLosing, indirizzi IP, segnale, rete bloccata per l'app, motivo Android (API 34+), rete principale del telefono, schermo acceso/spento, app in primo piano/background, "La camera ha spento il suo Wi-Fi", età della rete a ogni caduta. In attesa di un log nuovo.

## Interfaccia (Material You, design nel canvas "Fuji Transfer · Material You")
- Galleria per giorni con celle scheletro durante l'elenco, selezione con animazione, pressione lunga = originale (foto zoomabile, video), scheda dettagli come bottom sheet.
- Filtri: "Data" (DateScreen) e "Luogo" (MapScreen: zone raggruppate, chip fuori zona, ricerca, foglio aperto/compatto/nascosto trascinabile). Metadati in SQLite (MetaStore, chiave nome|data).
- Download in background con notifica che riapre la schermata download.
- Icona: concept "Telemetro e Wi-Fi" come icona adattiva con livello monocromatico.

## Restyle Material 3 Expressive (3 nov 2026, sera)
- Design nel canvas "Fuji Transfer · Expressive" (versione sobria: solo cerchi e angoli arrotondati, scala testo M3 standard, peso max 500). Il vecchio canvas "Material You" resta per confronto.
- Fatto sulle View, senza Compose (scelta: la parte difficile è la connessione, non la UI; Compose eventualmente più avanti, una schermata alla volta).
- Nuova `WavyProgressBar` (estende ProgressBar, disegnata a mano): lineare o circolare a seconda delle proporzioni, determinata o indeterminata, onda ferma con animazioni di sistema spente. Usata per: anello della connessione (al posto di M3Spinner, che resta nel codice ma non è più usato), barra del download, anello di ogni file, barra dei passi, striscia download in galleria, barra del visore, barra dei lavori (4 dp: piatta).
- Gruppo Wi-Fi/USB a pulsanti collegati (2 dp di stacco, angoli interni 8 dp, scelto = primario e tutto tondo, si allarga con overshoot: `moveTabs`).
- Pulsanti: premuti stringono gli angoli (selector con dissolvenza). Coppie collegate in download (Annulla / Torna alla galleria) e nella scheda foto (Seleziona / Scarica ora).
- Galleria: barra di selezione mobile (pillola primary container + pulsante download 64 dp, angoli 20).
- Backup prima delle modifiche: `docs/backup-2026-11-03-expressive/` (res e java completi).

## Filtro per pellicola (4 nov 2026, sera)
- Icone: 20 simboli di simulazione pellicola estratti da XApp (`res/drawable/r_fs_*` del base.apk, 2.7.6) e salvati come `res/drawable/film_*.xml` (neri, 20 dp, si tingono col tema). Originali decodificati in `docs/film-raw` (binari). Sono di Fujifilm: solo uso personale, non pubblicare.
- `FilmFilter.kt` (nuovo): `Films.icon(nome)` (nome come lo dà `Exif.filmName/monochrome`), `FilmOption`, `FilmSheet` (bottom sheet a tessere 3 per riga, scelta multipla, coppia «Azzera» / «Mostra N foto»).
- `GalleryChrome.kt`: chip «Pellicola» dopo «Luogo» (campi `film`, `filmLabel`, azioni `onFilm`, `onClearFilm` con default).
- `MainActivity.kt`: `films`, `filmSheet`, `matchesFilm` in `matches()`, `applyFilms`, `clearFilm`, `filmOptions`. Come per il luogo, scegliere una pellicola porta «Nuove» su «Tutte».
- Non compilato (nessun Docker/SDK qui). Backup: `docs/backup-2026-11-04-film/`. Studio Portrait non ha un simbolo: usa `ic_film`.
