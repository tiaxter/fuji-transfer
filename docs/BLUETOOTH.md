# Bluetooth LE di Fujifilm: come XApp accende il Wi-Fi della camera

Fonte: decompilazione di XApp 2.7.6, pacchetto `com.fujifilm.xapp.model.bleconnect`
(`BTCamera`, `BTManager`, `BTRequester`, `BTConstansKt`) e `ui/camera_connect`, `model/camera_connect/WiFiHandOverService`.
Letto dal codice, non provato su una camera reale.

## Perché serve
Sulle camere recenti il Wi-Fi per il trasferimento non si accende dal menu della camera: è il telefono che lo chiede
via Bluetooth. Senza questa parte l'app non può mai collegarsi in Wi-Fi.

## Ricerca (scansione)
- Filtri come XApp: servizi `AF854C2E-…` (file transfer, firmware vecchi), `123D8F06-…`, `A9D2B304-…`, `462426E1-…`,
  `CC74BD26-…`, `EB894E6E-…`, `731893F9-…`, `804DAA8E-…`, oppure dati del produttore con company id `0x04D8` (1240).
- **Firmware vecchi** (pubblicizzano `AF854C2E`): in modalità abbinamento i dati produttore sono `02 k0 k1 k2 k3`,
  cioè la chiave di abbinamento da 4 byte. Le prime 4 lettere del nome sono il "numero di serie breve" usato per
  ritrovare la camera.
- **Firmware "RED"** (direttiva UE sulle apparecchiature radio): dati produttore `01` + 5 caratteri ASCII del numero
  di serie breve. La camera è in modalità abbinamento quando pubblicizza il servizio `A9D2B304-…`. Si ritrova dopo
  per indirizzo Bluetooth.

## Collegamento
`connectGatt` (LE) → `requestMtu(185)` → `discoverServices`.

**Servizio giusto per ogni caratteristica.** Le caratteristiche di "dispositivo collegato" (nome del telefono, chiave,
numero di identificazione, info app) esistono sia nel servizio vecchio `91F1DE68-…` sia in quello RED `123D8F06-…`;
il MAC sta in `117C4142-…` (vecchio) o `A9D2B304-…` (RED). XApp sceglie sempre il servizio in base al tipo di camera
(`BTCameraService.SERVICE_MAP_NON_RED` / `SERVICE_MAP_RED`). La prima versione di questa app prendeva il primo trovato:
su una camera RED che espone anche il servizio vecchio la scrittura falliva con GATT 133. Corretto.

**Tentativi.** Come XApp, ogni lettura/scrittura viene ripetuta fino a 3 volte; se la camera chiede un collegamento
cifrato (stati 5, 8, 15, 137, oppure 133 su camere RED) l'app chiede il bonding ad Android e aspetta.

### Firmware vecchi
1. legge il numero di serie `2A25`;
2. scrive la chiave da 4 byte in `ABA356EB-…` (PAIRING_KEY), invariata; alle connessioni successive scrive la chiave salvata;
3. scrive il nome del telefono in `85B9163E-…` (UTF-8 + NUL);
4. se presente, scrive `80 01 01` in `8B5ECF55-…` (app = XApp, versione 0x0101).

### Firmware RED
1. solo al primo abbinamento: nome del telefono in `85B9163E-…`;
2. legge `F557D96B-…` (numero di identificazione, uint32 LE). La lettura richiede il bonding: Android mostra la sua
   richiesta di abbinamento. XApp rilegge fino a 20 volte con 1 s di pausa finché il valore non è diverso da 0
   (la persona conferma sulla camera); questa app aspetta fino a 40 s;
3. primo abbinamento: id = valore letto OR `0x20000000`; dopo: id salvato. Lo riscrive in `F557D96B-…` (uint32 LE);
4. legge `2A25`, riscrive il nome, scrive `80 01 01` in `8B5ECF55-…`.

## Riuso dell'abbinamento fatto con XApp
- Con firmware RED il bonding è del telefono, valido per tutte le app. Il numero che XApp riscrive alla camera è sempre
  (numero letto | `0x20000000`): questa app legge lo stesso numero e scrive lo stesso valore, senza registrarsi di nuovo.
- Con firmware vecchi la chiave sta nella memoria privata di XApp. Se la camera è collegata a XApp, il collegamento
  Bluetooth è condiviso tra le app e già autenticato: l'app chiede il Wi-Fi senza chiave. Se la camera permette di
  leggere la chiave, viene usata.
- Una camera collegata a XApp smette di farsi trovare con la ricerca Bluetooth: l'app la cerca prima tra i dispositivi
  già collegati e abbinati del telefono, poi con la ricerca (prima con filtri, poi senza).

## Accensione del Wi-Fi
1. notifiche (o indicazioni, a seconda della caratteristica) su `A68E3F66-…` (AP_STATE);
2. scrive `03 00` in `600655E6-…` (FUNCTION_LAUNCH_REQUEST, valore 3 = InCameraViewing: sfogliare le foto dal telefono;
   1 = ImportImage, 4 = RemoteShooting);
3. AP_STATE (uint16 LE): `0x8000` spento, `0x8002` in avvio, `0x8001` acceso, `0x8003` acceso per trasferimento
   prenotato. Timeout di XApp: 20 s;
4. se torna spento, legge `1587B102-…` (dettagli errore, uint16): 2 = camera occupata per poco (XApp riprova dopo
   200 ms), 6 = in modalità USB, 3 = occupata a lungo, 5 = configurazione iniziale da completare, ecc.;
5. legge SSID `BF6DC9CF-…`, password `E809256A-…`, MAC `49A12959-…` (stringhe terminate da NUL);
6. `WifiNetworkSpecifier` con SSID esatto, BSSID = MAC (con `-` → `:`), WPA2 con la password, senza capacità Internet.
   Poi PTP/IP su 192.168.0.1:55740 come prima.

## Cosa fa in più questa app
- Se il BSSID letto non funziona riprova con il solo SSID.
- Se la rete Wi-Fi cade durante un download, la richiede di nuovo via Bluetooth (stesso collegamento BLE) e riprende.
- Polling di AP_STATE se le notifiche non arrivano.

## Miniature
- USB: `GetThumb` 0x100A (`Java_SDK_ReadThumbnail`).
- Wi-Fi: XApp usa l'operazione Fuji 0x9055 (`GetExtensionThumb`), stessa forma di chiamata (un parametro, l'handle).
  Questa app prova 0x9055 e, se la camera risponde "non supportata", passa a 0x100A.
- Anteprima più grande: XApp la ottiene chiedendo alla camera una versione compressa dell'immagine (impostazione di
  compressione via proprietà del dispositivo, solo Wi-Fi). Non implementato.

## Sequenza reale di XApp prima della richiesta Wi-Fi (X-M5, log Bluetooth del telefono, 2 novembre)
Ricavata dal log dello stack Bluetooth (bugreport) durante un'importazione riuscita con XApp. Il log riporta handle e
ordine delle scritture e delle iscrizioni, non i valori; i valori sono presi dal codice di XApp.
1. Scrittura numero di identificazione (F557D96B), nome telefono (85B9163E), info app (8B5ECF55).
2. Iscrizione a: stato Wi-Fi A68E3F66 (indicazione), stato trasferimento BD17BA04 (indicazione), stato sincronizzazione
   data F9150137, stato sincronizzazione posizione AD06C7B7, avvio remoto 7170FD5A.
3. Iscrizione a SSID BF6DC9CF e impostazione log 75823784; **scrittura "image transfer setting ex" 98934B2C = 01**.
4. Iscrizione a stato camera E6692C5C, impostazione posizione AAB609C4, ridimensionamento 82A9F452, intervallo
   posizione C95D91AE (XApp scrive anche l'intervallo, valore dalle sue impostazioni: la mia app lo salta).
5. Scrittura ora UTC e fuso C52EDBCE (servizio E872B11F): 12 byte, anno u16, mese, giorno, ora, minuti, secondi in UTC,
   offset int32 in centesimi di ora (+1 h = 100, +5:30 = 550), flag ora legale.
6. Richiesta Wi-Fi 600655E6 = 03 00.
La mia app prima faceva solo i punti 1 e 6. Ora li fa tutti tranne la scrittura dell'intervallo posizione.
