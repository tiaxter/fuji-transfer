# Analisi di FUJIFILM XApp 2.7.6 (com.fujifilm.xapp) — trasferimento foto

Metodo: decompilazione Java con jadx (base.apk), disassemblaggio arm64 delle librerie native
(`libFTLPTPIP.so` per il Wi-Fi, `FTLPTP.so` + `libusb-1.0.so` per l'USB, `libXAPI.so` come SDK sopra di esse).
Le affermazioni sono lette dal codice; nulla è stato provato su una camera reale.

## Architettura
- Java/Kotlin: `ControlFFIR`, `CameraConnectWrapper`, `XSDK`, `ReceiveImageModel`. Fa solo UI, rete e ciclo di download.
- Nativo: l'intero protocollo (PTP, Fuji) è in C++ dentro le `.so`. La parte Java passa i socket al nativo come file descriptor.
- USB: `XSDK_SetUSBDeviceHandle(fd, rawDescriptors)`: il fd di `UsbManager` va a `libusb`.

## Protocollo Wi-Fi (verificato nel codice nativo)
- Camera a 192.168.0.1. Porte (enum `SOCKET_PORT_NO` in `ControlFFIR`): 55740 `PORT_BASE_SOCK` (comandi),
  55741 eventi, 55742 live view (così nel nativo, `MngPTP::OpenEvent`/`OpenThroughPicture`; l'enum Java li
  chiama al contrario `PORT_THROUGH_SOCK`/`PORT_EVENT_SOCK`, ma conta il numero di porta). **Per l'import delle foto XApp apre solo
  55740**: le altre due le apre `InitiateOpenCapture` (scatto remoto / live view), quindi per scaricare basta un socket.
- Handshake PTP/IP: richiesta di 82 byte (0x52), ack da 68 byte (0x44), rifiuto da 12 byte. Il pacchetto di init è
  costruito da un template dentro `libFTLPTPIP.so` (`MngPTP::Open`): len=82, type=1, magic=0x8f53e4f2, poi
  l'identificativo client e il nome del telefono.
- **Identificativo client** (template a 0x1d3d0): `ad a5 48 5d 87 b2 7f 0b d3 d5 de d0` fissi, poi **i byte 24..27 sono
  l'indirizzo IP del telefono** sulla rete della camera: `MngPTP::Open` chiama `getsockname` e lo scrive come uint32
  little-endian (192.168.0.129 → `81 00 a8 c0`). fudge invece lascia zeri. Il nome segue dal byte 28, un carattere
  ogni 2 byte (solo ASCII); XApp usa il "nome terminale" che scrive anche via Bluetooth.
- Se la risposta è di 12 byte con tipo 5 e codice 0x2019 (busy), XApp aspetta 0,5 s e riprova, per circa 2,5 s.
- Sull'X-M5 (log del 2 novembre) la connessione TCP viene accettata ma la camera non risponde all'init, né con GUID
  casuale né con i 16 byte del template e zeri al posto dell'IP; dopo circa 20 s chiude la porta. Ipotesi (da verificare
  sulla camera): controlla che i byte 24..27 corrispondano all'IP del telefono.
- Dopo l'handshake: contenitori PTP come su USB (lunghezza, tipo, codice, transaction id).
- **Impostazione della sessione (la parte che mancava).** Dopo OpenSession la camera non risponde alle richieste
  PTP normali finché il client non dice quale funzione vuole: senza questo passo l'X-M5 resta in attesa e dopo un
  po' mostra "not found, please check the app and select that function again". Sequenza di XApp per l'import
  (`CameraConnectModel.setFunctionMode(20, 0xDF28, 3)`, `ImportImageModel.setImageViewFunctionMode`) e di fudge
  (`fuji_setup` in `modules/libfuji/lib/fuji.c`, github.com/petabyt/fudge):
  1. pausa di 50 ms, OpenSession con sessione 1 e transaction id 1, **niente GetDeviceInfo**;
  2. lettura della lista eventi (proprietà 0xD212: u16 numero, poi coppie u16 codice + u32 valore) finché
     CameraState 0xDF00 non esce da 0 (= in attesa di accesso); 0xD222 dà il numero di oggetti;
  3. lettura delle versioni 0xDF22, 0xDF25, 0xDF21, 0xDF24;
  4. ClientState 0xDF01 = **20** (visualizzazione immagini remota, XApp) se il Wi-Fi è stato acceso via Bluetooth
     e 0xDF24 ≥ 0x20007; altrimenti il modo classico che fudge ricava dallo stato (5 = remoto). La risposta può
     arrivare tardi: secondo fudge le camere recenti possono mostrare una conferma sul display a questo punto;
  5. configurazione galleria XApp: 0xDF28 riletto e riscritto (XApp rifiuta versioni > 3), 0xD226 = 0, 0xD227 = 0,
     poi 0x9054 e 0x9055 sull'oggetto 0x10000001, 0x9050, 0xD22B, 0x9053(0, 30000), 0xD620, 0xD621;
  6. modo classico con stato remoto: InitiateOpenCapture, connessione alle porte 55741 e 55742,
     TerminateOpenCapture, poi DF00 = 6, DF01 = 11, DF25 = 5.
- Handle degli oggetti in Wi-Fi: 1..numero oggetti (0xD222), non GetObjectHandles (non supportato).
- Dimensioni reali: ObjectInfo riporta una dimensione fittizia finché non si imposta 0xD226 = 2 (modo XApp, file
  originale) o 0xD227 = 1 (modo classico). Si impostano prima di GetObjectInfo/GetPartialObject.
- Miniature: sulle camere con 0xDF24 > 0x20006 GetThumb si blocca se non è preceduto da GetObjectInfo dello stesso
  oggetto (fudge). GetPartialObject a blocchi da 1 MiB.
- Il socket lo apre Java (timeout di connessione 5 s), lo passa al nativo e poi chiude l'oggetto Socket Java.

## Operazioni PTP usate (codici dalle funzioni `FTL_PTP_*`)
Standard: 0x1001 GetDeviceInfo, 0x1002 OpenSession, 0x1004 StorageIDs, 0x1007 ObjectHandles, 0x1008 ObjectInfo,
0x1009 GetObject, 0x100A GetThumb, 0x101B GetPartialObject, 0x100B DeleteObject.
Estensioni Fuji:
| Codice | Funzione | Note |
|---|---|---|
| 0x902B | GetDevicePropDescAll | DeviceInfo Fuji |
| 0x9050 / 0x9051 / 0x9052 | Get/SetSearchMode, GetSearchModeDesc | filtri di ricerca |
| 0x9053 | GetSpecifiedObjectCountGroupByDate | conteggio per data |
| 0x9054 | GetExtensionObjectInfo | formato non decodificato |
| 0x9055 | GetExtensionThumb | |
| 0x9056 | GetExtensionPartialObject | parametri {handle, offsetLow, length, offsetHigh}: offset a 64 bit |
| 0x9060 | SetCameraEvent | |
Esiste anche `GetObjectPropValue` a 64 bit (`FTL_MTP_GetObjectPropValue_Int64`), usato per le dimensioni grandi.

## Come scarica l'app originale (Java)
- Un blocco = 20 971 520 byte (20 MiB) per chiamata a GetPartialObject (`GET_OBJECT_SIZE_ONE_TIME`).
- A ogni blocco alloca `new byte[20 MiB]` e lo accoda con `ArraysKt.plus(...)`, cioè copia l'intero array accumulato
  a ogni giro: l'intero file vive in RAM e la copia cresce a ogni blocco.
- Se un blocco fallisce (codici 0x2001, 0x2002, 0x9001 → "camera disconnessa", altri → errore di importazione),
  segnala l'errore e interrompe l'import. Non ho trovato nel ciclo né retry né ripresa dall'offset già ricevuto.
- Costanti Java in `XSDK`: `Timeout = 5000`, `RetryInterval = 200` (non so a quali operazioni si applichino).
- Wi-Fi: usa già `WifiNetworkSpecifier` e `bindProcessToNetwork`. Su questo punto la mia app non fa niente di diverso.

## Confronto con la mia app (core/)
| Aspetto | XApp originale | Mia app |
|---|---|---|
| Blocco | 20 MiB | 2 MiB |
| Errore a metà blocco | import interrotto | riconnessione, riparte dai byte già scritti |
| File in memoria | tutto, con copie ripetute | scrittura diretta su file `.part` |
| File ≥ 4 GiB | op 0x9056 con offset a 64 bit | op 0x9056 + ObjectSize a 64 bit (implementato, non provato su camera) |
| Rete Wi-Fi | specifier + bind processo | specifier + socket factory della rete |
| Pairing | ripete ~2,5 s | attende fino a 40 s |

## Ipotesi sul tuo problema (non verificata)
Con 20 MiB per blocco, un calo di segnale anche breve a metà blocco fa fallire la chiamata e l'app si ferma. File grandi
(RAF, video) hanno più probabilità di incappare in un calo e occupano molta RAM. Una prova su camera reale con la mia
app, e idealmente il log dell'app originale in un caso di errore, direbbe se è davvero questa la causa.

## Bluetooth e miniature (seconda analisi)
- Il Wi-Fi della camera lo accende XApp via Bluetooth LE: scrive 3 (InCameraViewIng, usato da XApp per
  l'import; fudge scrive 4 = RemoteShooting) nella caratteristica
  FUNCTION_LAUNCH_REQUEST, aspetta AP_STATE = 0x8001, legge SSID, password e MAC e si collega con quei dati.
  Sequenza completa in `docs/BLUETOOTH.md`.
- Miniature: GetThumb (0x100A) su USB, Fuji 0x9055 su Wi-Fi (un parametro, l'handle, come GetThumb).

## Da fare
- Metadati e dimensioni per la galleria senza leggere foto per foto: prove da fare in `docs/METADATI_E_DIMENSIONI.md`.
- Anteprima grande in Wi-Fi come XApp (immagine ricompressa dalla camera), non solo la miniatura.
- Decodificare il formato di 0x9054 e dei comandi di ricerca (0x9050–0x9053) se si vuole filtrare per data.
- Verificare 0x9056 e il tetto dei 4 GiB su una camera reale con un video lungo.
