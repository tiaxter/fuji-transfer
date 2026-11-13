# FUJIFILM XApp 2.7.6: funzioni e protocollo camera ⇄ app

Metodo: jadx sul `base.apk` (Java/Kotlin) e disassemblaggio arm64 di `libFTLPTPIP.so` (PTP/IP Wi-Fi), `libFFIR.so`
(SDK Wi-Fi, chiamato da `ControlFFIR`), `libXAPI.so` + `FTLPTP.so` (SDK USB, chiamato da `XSDK`).
Gli opcode e i codici proprietà sono letti dagli immediati passati a `MngPTP::ExecPTPOperation` e ai costruttori
`CParamFor{Get,Set}Int/Str`; nulla è stato provato su una camera reale. Dove l'interpretazione è dedotta e non
letta direttamente è scritto "probabile". Completa `ANALISI_XAPP.md` (import) e `BLUETOOTH.md` (abbinamento).

## 1. Funzioni dell'app e canale usato

| Funzione (pacchetto `ui/…`, `model/…`) | Canale | Modo camera (DF01) / richiesta BLE |
|---|---|---|
| Abbinamento, riconnessione (`pairing`, `bleconnect`) | BLE | — |
| Sincronizzazione ora e fuso | BLE `C52EDBCE`, `B9BFD37F` | automatica alla connessione |
| Invio posizione GPS (`location`) | BLE `0F36EC14` | intervallo da `C95D91AE` |
| Sfoglia e importa foto (`importimage`) | Wi-Fi PTP/IP | BLE FUNCTION_LAUNCH=3 → modo 20 (DF28 v3) |
| Trasferimento automatico / prenotato (`autoimagereceive`) | Wi-Fi | AP_STATE 0x8003, modo 21 (DF29 v3) |
| "Invia a smartphone" dal menu camera | Wi-Fi | modo 1 (IMAGE_RECEIVE), handle da D22B |
| Scatto remoto con live view (`liveview`) | Wi-Fi 55740 + 55741 + 55742 | modo 5 (DF24) o 22 (DF2A) |
| Scatto remoto senza Wi-Fi (`remote_release`) | BLE `7FCF49C6` | S0/S1/S2 |
| Accensione/spegnimento remoto | BLE `43070F6C`, `7170FD5A` | PowerOff=1, NormalBoot=0x100 |
| Aggiornamento firmware camera (`fw_update`, `autofwup`) | BLE (richiesta) + Wi-Fi (file) | FUNCTION_LAUNCH=5 → modo 19 (DF27 v1) |
| Backup / ripristino impostazioni (`backup_restore`) | BLE servizio file transfer | BACKUP/RESTORE_REQUEST |
| Preset IPTC (`iptc`) | BLE (stesso canale del backup, tipo 2) | IPTC_SETTING |
| Nome camera, ridimensionamento, log | BLE | vedi §5 |
| Log camera (XLog, "vital") verso server Fujifilm | BLE (richiesta) + Wi-Fi (file) | LOG_TRANSFER_REQUEST |
| Info obiettivo montato (`equipment`) | BLE servizio `15CA59FE` | sola lettura |
| Contatori stampa instax | BLE | sola lettura |
| Import via cavo (`usb`) | USB PTP (libusb) | — |
| Timeline, attività, stampe, login, sondaggi | solo server (AWS/Amplify) | nessuna comunicazione con la camera |

## 2. Trasporto Wi-Fi

- Camera 192.168.0.1. Handshake PTP/IP sulla 55740 come in `ANALISI_XAPP.md`.
- **Porte (dal codice nativo):** 55740 comandi; **55741 eventi** (`MngPTP::OpenEvent`); **55742 live view**
  (`MngPTP::OpenThroughPicture`). L'enum Java `SOCKET_PORT_NO` chiama 55741 `PORT_THROUGH_SOCK` e 55742
  `PORT_EVENT_SOCK`, ma Java passa solo il numero di porta e il nativo decide: valgono i nomi nativi (come fudge).
- Le due porte extra si aprono dopo `InitiateOpenCapture` (0x101C); se la connessione fallisce il nativo aspetta
  e riprova per un tempo limitato (durata esatta non verificata). Eventi: pacchetti fino a 0xFF00 byte, timeout 5 s. Live view: JPEG con intestazione (formato non decodificato).
- Errori SDK (Java `SDK_ERRCODE_*`): 0x2001 comunicazione, 0x2002 timeout, 0x1006 occupata, 0x3001 errore di sistema,
  0x3002 scheda piena, 0x3003 scheda protetta, 0x3005 scheda non disponibile, 0x9001 interno.

## 3. Opcode PTP (app → camera)

Direzione dati: "→" l'app invia dati, "←" la camera risponde con dati, "—" solo parametri.
Param = numero di parametri del comando.

### Standard PTP/MTP
| Opcode | Nome (funzione FTL) | Dati | Param | Uso nell'app |
|---|---|---|---|---|
| 0x1001 | GetDeviceInfo | ← | 0 | info camera (non usato in Wi-Fi all'avvio) |
| 0x1002 | OpenSession | — | 1 | sessione 1 |
| 0x1003 | CloseSession | — | 0 | |
| 0x1004 | GetStorageIDs | ← | 0 | |
| 0x1005 | GetStorageInfo | ← | 1 | |
| 0x1006 | GetNumObjects | — | 2 | |
| 0x1007 | GetObjectHandles | ← | 2 | non supportato in Wi-Fi |
| 0x1008 | GetObjectInfo | ← | 1 | info file |
| 0x1009 | GetObject | ← | 1 | |
| 0x100A | GetThumb | ← | 1 | miniatura USB |
| 0x100B | DeleteObject | — | 1 | |
| 0x100E | InitiateCapture | — | 2 | scatto (remoto Wi-Fi) |
| 0x1014 | GetDevicePropDesc | ← | 1 | |
| 0x1015 | GetDevicePropValue | ← | 1 | lettura proprietà (§4) |
| 0x1016 | SetDevicePropValue | → | 1 | scrittura proprietà (§4) |
| 0x1017 | ResetDevicePropValue | — | 1 | |
| 0x1018 | TerminateOpenCapture | — | 1 | fine live view |
| 0x101B | GetPartialObject | ← | 3 | download a blocchi |
| 0x101C | InitiateOpenCapture | — | 2 | avvio live view / remoto |
| 0x9803 | MTP GetObjectPropValue | ← | 2 | dimensione a 64 bit (D802) |
| 0x9804 | MTP SetObjectPropValue | → | 2 | |
| 0x9805 | MTP GetObjectPropList | ← | 5 | |

### Estensioni Fujifilm (Wi-Fi, `libFTLPTPIP`/`libFFIR`)
| Opcode | Nome | Dati | Param | Uso |
|---|---|---|---|---|
| 0x9020 | InitiateMovieCapture | — | 2 | avvio video (remoto) |
| 0x9021 | TerminateMovieCapture | — | 1 | stop video |
| 0x9022 | GetPostview | ← | 0 | anteprima dopo lo scatto (buffer 12 MB) |
| 0x9023 | StepZoom | — | (dir, velocità) | zoom a passi (obiettivi motorizzati) |
| 0x9024 | StartZoom | — | (dir, velocità) | zoom continuo |
| 0x9025 | StopZoom | — | 0 | |
| 0x9026 | LockS1Lock | — | 1 (area AF) | mezza pressione / AF su punto toccato |
| 0x9027 | UnlockS1Lock | — | 0 | |
| 0x902B | GetDevicePropDescAll | ← | 0 | tutte le proprietà in un colpo (remoto) |
| 0x902C | StepShutterSpeed | — | 1 (direzione) | ghiera tempi ±1 |
| 0x902D | StepFnumber | — | 1 | ghiera diaframma ±1 |
| 0x902E | StepExposureBias | — | 1 | compensazione ±1 |
| 0x9030 | CancelInitiateCapture | — | 0 | annulla autoscatto/posa |
| 0x9040 | FmSendObjectInfo | → | 2 | firmware: nome interno + dimensione |
| 0x9041 | FmSendObject | → | 0 | firmware in un pezzo |
| 0x9042 | FmSendPartialObject | → | 2 | firmware a blocchi |
| 0x9050 | GetSearchModeDescAll | ← | 0 | filtri disponibili |
| 0x9051 | SetSearchModeAll | → | 0 | imposta filtri (data, cartella, voto, formato, soggetto) |
| 0x9052 | GetSearchModeAll | ← | 0 | filtri correnti |
| 0x9053 | GetSpecifiedObjectCountGroupByDate | ← | 2 (offset, n) | conteggio per giorno |
| 0x9054 | GetExtensionObjectInfo | ← | 1 | ObjectInfo esteso |
| 0x9055 | GetExtensionThumb | ← | 1 | miniatura Wi-Fi |
| 0x9056 | GetExtensionPartialObject | ← | 4 | {handle, offset basso, lunghezza, offset alto} |
| 0x9057 | GetSpecifiedObjectCountOrderByRating | ← | 3 | conteggio per voto |
| 0x9058 | SetRating | — | 2 (handle, voto) | voto stelle dal telefono |
| 0x9060 | SetCameraEvent | → | 0 | notifiche app→camera (sotto) |

**SetCameraEvent 0x9060** (struttura codice evento, tipo dati, valore):
- 0x1001 (`EVENT_CODE_IMAGE_TRANSFER_COMPLETED`), tipo 6 (UINT32), valore = numero di foto salvate: dopo l'import.
- 0x1002 (`EVENT_CODE_IMAGE_DELETE_BODY_FW`), tipo 0: dopo l'aggiornamento firmware, chiede di cancellare il file.

### Solo USB (`libXAPI`, chiamato da `XSDK`)
| Opcode | Nome | Uso nell'app |
|---|---|---|
| 0x9008 | GetSpecifiedObjectInfo | info file |
| 0x901B | GetSpecifiedPartialObject (4 param, offset 64 bit) | download |
| 0x100A | GetThumb (timeout 60 s) | miniature |
| 0x1015 su D212/D20E | lista oggetti (probabile) | elenco file |

In `libXAPI` ci sono anche backup impostazioni (0x1008/0x1009/0x100C/0x100D), impostazioni RAW (0x1015/0x1016),
invio RAW dal PC e firmware via USB (0x900C/0x900D): l'app non li espone (i metodi nativi di `XSDK` non li includono).

## 4. Proprietà del dispositivo (Get/SetDevicePropValue)

Tipi PTP: 2 UINT8, 4 UINT16, 6 UINT32, 8 UINT64, 0xFFFF stringa.

### Sessione e funzioni (Wi-Fi)
| Codice | Tipo | Dir. | Significato |
|---|---|---|---|
| 0xD212 | lista | ← | lista eventi a polling: u16 n, poi n × (u16 codice, u32 valore) |
| 0xD220 | | ← | segnalata nella lista eventi (significato non decodificato) |
| 0xD222 | | ← | numero di oggetti (cambia → evento 11/12) |
| 0xD226 | UINT16 | → | ImageForceCompression: 0 off, 1 on, 2 full (file originale) |
| 0xD227 | UINT16 | → | ImageCompressionRealInfo: dimensioni reali in ObjectInfo |
| 0xD22B | UINT32 | ← | handle dell'oggetto che la camera vuole inviare (modo 1) |
| 0xD22C | UINT32 | ← | numero di log disponibili |
| 0xD22D | UINT16 | → | esito trasferimento log: 0 NG, 1 OK, 2 annullato |
| 0xD22E | UINT16 | ↔ | compressione oggetto (anteprima grande ricompressa) |
| 0xD22F | stringa | ← | nome file firmware corpo attuale |
| 0xD230 | UINT16 | → | ForceMode (USB) |
| 0xD235 | UINT32 | → | dimensione dei blocchi parziali |
| 0xD244 | UINT8 | ↔ | stato doppio slot SD |
| 0xD246 | UINT8 | ↔ | modo drive (singolo/raffica/…) |
| 0xD041 | UINT16 | ↔ | ConnectionSetting (USB) |
| 0xD400, 0xD401 | | ← | nella lista eventi → richiesta remoto (evento 17), probabile |
| 0xD500 | stringa | → | GPS via PTP (nell'SDK, non chiamato da Java: l'app manda il GPS via BLE) |
| 0xD52F | | ← | camera chiede il GPS (evento 13) |
| 0xD600 | UINT16 | → | ricerca: fuso orario |
| 0xD601–0xD605 | | ↔ | ricerca: data, cartella, voto, formato, soggetto |
| 0xD620 | UINT32 | ← | numero oggetti che rispettano il filtro |
| 0xD621 | | ← | handle degli oggetti filtrati |
| 0xD7B6, 0xDE82 | | | usati da PausePolling (significato non decodificato) |
| 0xD801 | | ← | proprietà oggetto (thread di lettura, MTP) |
| 0xD802 | UINT64 | ← | dimensione oggetto a 64 bit (MTP GetObjectPropValue) |
| 0xDF00 | UINT16 | ↔ | CameraState / funzione camera (0 = in attesa, 6 = neutro) |
| 0xDF01 | UINT16 | → | ClientState / modo funzione (§6) |
| 0xDF21 | UINT32 | ↔ | versione PhotoReceive |
| 0xDF22 | UINT32 | ↔ | versione PhotoView |
| 0xDF24 | UINT32 | ↔ | versione Remote |
| 0xDF25 | UINT32 | ↔ | versione RemotePhotoView |
| 0xDF27 | UINT32 | ↔ | versione FirmwareDataTransfer (XApp scrive 1) |
| 0xDF28 | UINT32 | ↔ | versione RemotePhotoViewEx (XApp scrive 3) |
| 0xDF29 | UINT32 | ↔ | versione ReservedPhotoReceivedEx (XApp scrive 3) |
| 0xDF2A | UINT32 | ↔ | versione RemoteEx (live view nuova) |
| 0xDF31 | UINT32 | ↔ | versione GPSSet |
| 0xDF41 | | ← | numero di foto prenotate per il trasferimento (evento 47) |
| 0xD2B7 | stringa | ← | info cornice trasparente video |

### Scatto remoto (`CameraControlActivity.CameraProperty`)
Lette con GetDevicePropDescAll, cambiate con SetDevicePropValue o con i comandi Step*.

| Codice | Nome nell'app | | Codice | Nome nell'app |
|---|---|---|---|---|
| 0x5001 | batteria | | 0xD019 | modo registrazione abilitato |
| 0x5003 | dimensione immagine | | 0xD01D | macro |
| 0x5005 | bilanciamento del bianco | | 0xD028 | colore indicatore esposizione/tempi |
| 0x5007 | diaframma | | 0xD02A | ISO |
| 0x500A | modo messa a fuoco | | 0xD02B | ISO video |
| 0x500C | flash | | 0xD170 | zoom |
| 0x500D | tempo di posa | | 0xD17C | stato blocco S1 (AF) |
| 0x500E | modo di scatto (P/A/S/M) | | 0xD209 | colore blocco S1 |
| 0x5010 | compensazione esposizione | | 0xD21B | errore dispositivo |
| 0x5012 | autoscatto | | 0xD229 / 0xD22A | scatti / tempo video rimanenti |
| 0xD001 | simulazione pellicola | | 0xD240 | tempo di posa esteso |
| 0xD018 | gamma dinamica (CCD mode) | | 0xD241 | dimensione pixel |
| 0xD242 | batteria a più livelli | | 0xD243 | tipo otturatore attuale |
| 0xD245 | modo selezione AF | | 0xD246 | modo drive |

## 5. Bluetooth LE (UUID completi in `BTConstansKt`)

Interi little-endian; le stringhe sono UTF-8 con NUL finale. Servizi: Camera Control `6514EB81`, Camera State
`4C0020FE`, Camera Setting `4E941240`, Current Time `E872B11F`, Current Location `3B46EC2B`, File Transfer
`AF854C2E` (e `462426E1`/`CC74BD26` per X half), Lens `15CA59FE`, Connected Device `91F1DE68` / `123D8F06` (RED),
Camera Info `117C4142` / `A9D2B304` (RED), Startup `731893F9` / `804DAA8E` (RED).

### App → camera (scrittura)
| Caratteristica | UUID | Payload |
|---|---|---|
| FUNCTION_LAUNCH_REQUEST | 600655E6 | u16: 1 ImportImage, 3 InCameraViewing, 4 RemoteShooting, 5 UpdateFirmware |
| SHOOTING_REQUEST | 7FCF49C6 | u16: 0 rilascio (S0), 1 mezza pressione (S1), 2 scatto (S2) |
| POWER_CONTROL_REQUEST | 43070F6C | u16: 1 spegni, 0x0100 accensione normale |
| REMOTE_BOOT_SETTING | 7170FD5A | u8: 0/1 (consentire l'accensione da remoto) |
| BACKUP_REQUEST | F14E7A88 | u16: 1 backup impostazioni, 2 preset IPTC |
| RESTORE_REQUEST | F1897EB9 | u16: 1 ripristino, 2 IPTC |
| LOG_TRANSFER_REQUEST | 2674F0B0 | u16: 1 XLog, 2 vital |
| FWUPDATE_REQUEST | B1307521 | 92 byte: u16 tipo, nome prodotto[64], nome file[15], u32 dimensione, versione[7] |
| UTC_AND_TIMEZONE | C52EDBCE | 12 byte: u16 anno, mese, giorno, ora, min, sec (UTC), i32 offset in centesimi d'ora, u8 ora legale |
| DATE_TIME | B9BFD37F | 7 byte: u16 anno, mese, giorno, ora, min, sec (ora locale, firmware vecchi) |
| LOCATION_AND_SPEED | 0F36EC14 | 23 byte: i32 lat×1e7, i32 lon×1e7, i32 altitudine m, i32 velocità cm/s, u16 anno, mese, giorno, ora, min, sec (UTC) |
| LOCATION_SYNC_SETTING | AAB609C4 | u16: 0/1 |
| LOCATION_SYNC_CYCLE | C95D91AE | u16 secondi |
| IMAGE_RESIZE_SETTING | 82A9F452 | u8: 0/1 (trasferimento ridotto) |
| IMAGE_RESIZE_RATE | 2A125640 | u16: 0 XS, 1 S |
| IMAGE_TRANSFER_SETTING_EX | 98934B2C | u8 (XApp scrive 1; RAF=1, HEIF non impostato=0) |
| IPTC_SETTING | DEEF7187 | u16 |
| LOGGING_SETTING | 75823784 | struttura `LogSetting` |
| CAMERA_SSID_NAME_STRING | BF6DC9CF | stringa: rinomina la camera (e quindi l'SSID) |
| CONNECTED_DEVICE_NAME_STRING | 85B9163E | nome del telefono |
| CONNECTED_DEVICE_IDENTIFICATION_NUMBER | F557D96B | u32 (RED): numero letto OR 0x20000000 |
| PAIRING_KEY | ABA356EB | 4 byte (firmware vecchi) |
| CONNECTED_APPLICATION_INFORMATION | 8B5ECF55 | `80 01 01`: app XApp, versione 0x0101 |
| CONNECTED_DEVICE_IMAGE_RECEIVE_STATE | A80BE3F8 | u8: telefono pronto a ricevere |
| CONNECTED_DEVICE_DISCONNECTED_REASON | 7EDE1988 | u16: 1 utente, 2 timeout |
| FILE_TRANSFER_INDEX | 051DD980 | u32 indice del file |
| FILE_INFORMATION | C922AC69 | 44 byte: nome[40], u32 dimensione (ripristino) |
| FILE_PARTIAL_SIZE | 7F3400FE | u32 (blocchi da 120 byte) |
| FILE_PARTIAL_DATA | AC0C799A | u16 sequenza, dimensione, dati, u16 checksum (ultimo blocco: sequenza 0xFFFF) |
| FILE_TRANSFER_RESULT | 68052E8A | u16 esito |

### Camera → app (lettura o notifica)
| Caratteristica | UUID | Contenuto |
|---|---|---|
| AP_STATE | A68E3F66 | u16: 0x8000 spento, 0x8001 acceso, 0x8002 in avvio, 0x8003 acceso per trasferimento prenotato |
| STATE_ERROR_DETAILS | 1587B102 | u16: 1 FTP, 2/3 occupata breve/lunga, 4 fine operazione, 5 configurazione iniziale, 6 USB, 7/8 tethering, 9 sviluppo RAW USB, 10 webcam, 11 live remote, 12 immagine non trasferita, 13 Frame.io |
| TRANSFER_STATE | BD17BA04 | 0x8001 foto da trasferire (avvia il trasferimento automatico) |
| DATE_SYNC_STATE / LOCATION_SYNC_STATE | F9150137 / AD06C7B7 | la camera chiede ora / posizione |
| CAMERA_VITAL_STATE | E6692C5C | 0 nessun aggiornamento, 1 aggiornamento (log vital) |
| BACKUP_STATE / RESTORE_STATE / LOG_TRANSFER_STATE | 11438C83 / 4B3A413C / 2F6CB772 | 0 non trasferibile, 1 trasferibile |
| FWUPDATE_STATE | 049EC406 | 0 NG, 1 OK, 0x10 occupata, 0x20 obiettivo, 0x30 accessorio, 0x40/0x41 batteria, 0x50 prodotto, 0x60 nome file, 0x70 dimensione, 0x80 versione, 0x100 annullato |
| FWUPDATE_FILE_INFORMATION | 20EA1FAC | info file firmware |
| CARD_STATE | 34D8C8DE | stato scheda |
| FILE_NUMBER | E3FBBFCF | numero di file (backup) |
| FILE_INFORMATION / FILE_PARTIAL_DATA | C922AC69 / AC0C799A | lettura del file di backup a blocchi |
| FILE_TRANSACTION_STATE | 2E27ED9F | avanzamento trasferimento file |
| SSID, password, MAC | BF6DC9CF, E809256A, 49A12959 | credenziali dell'access point |
| POWER_KEY_STATE | F90F7D3A | u16: 0x100 spenta da remoto, 0x200 spenta, 0x201 accesa |
| WAKEUP_MODE, STARTUP_VALUE, BLE_PROTOCOL_VERSION, MOVIE_BUTTON, Y_NUMBER, SERIAL | servizio Camera Info | capacità e identità |
| Obiettivo: nome, firmware, seriale | 68887A7A, 621A98D8, DF287E0C | |
| Contatori stampa instax | 0E66288A, BC384197, 8FBFDE61 | |
| PAIRING_SMART_DEVICE_NUM | 8814441B | telefoni abbinati |
| Standard | 2A00, 2A24–2A29 | nome, modello, seriale, firmware |

**Advertising:** dati produttore con company id 0x04D8. Firmware vecchi: `02` + chiave da 4 byte; RED: `01` +
5 caratteri del seriale; il byte successivo è lo stato del tasto di accensione / avvio remoto.

## 6. Modi di funzione (DF01 = ClientState) e sequenze

| Valore | Costante Java | Versione richiesta | Chi lo usa |
|---|---|---|---|
| 1 | SDK_MODE_IMAGE_RECEIVE | DF21 | "Invia a smartphone" dal menu camera |
| 5 | SDK_MODE_REMOTE | DF24 (≥ `REMOTE_MODE_VERSION`) | scatto remoto classico |
| 6 | SDK_MODE_NEUTRAL20 (su DF00) | — | uscita / cambio funzione |
| 19 | SDK_MODE_FIRMWARE_DATA_TRANSFER | DF27 = 1 | aggiornamento firmware |
| 20 | SDK_MODE_IMAGE_VIEW_V2 | DF28 = 3 | sfoglia e importa |
| 21 | SDK_MODE_RESERVED_PHOTO_RECEIVED20 | DF29 = 3 | trasferimento automatico |
| 22 | SDK_MODE_IMAGE_LIVE_VIEW | DF2A | live view nuova (prima DF00 = 6) |

`CameraConnectRepository` sceglie il modo dalla richiesta BLE: InCameraViewing → (20, DF28, 3); trasferimento
prenotato → (21, DF29, 3); UpdateFirmware → (19, DF27, 1). Sequenza: GetFunctionVersion, SetFunctionVersion
(XApp rifiuta versioni camera più alte di quelle note), poi SetFunctionMode.

- **Remoto:** DF01 = 5 o 22 → InitiateOpenCapture (timeout 30 s) → socket 55741 (eventi) e 55742 (live view) →
  GetDevicePropDescAll → comandi Step*/Zoom/S1/InitiateCapture → GetPostview → TerminateOpenCapture.
- **Firmware:** BLE FWUPDATE_REQUEST (la camera verifica e risponde in FWUPDATE_STATE) → FUNCTION_LAUNCH = 5 →
  Wi-Fi → modo 19 → FmSendObjectInfo(nome, dimensione) → FmSendPartialObject a blocchi → SetCameraEvent 0x1002.
- **Log:** BLE LOG_TRANSFER_REQUEST → Wi-Fi → D22C (quanti) → lettura → D22D (esito); i file `.gz` vanno al server.
- **Backup/ripristino:** solo BLE: BACKUP_REQUEST → BACKUP_STATE = 1 → FILE_NUMBER / FILE_TRANSFER_INDEX /
  FILE_INFORMATION → FILE_PARTIAL_DATA a blocchi → FILE_TRANSFER_RESULT. Il ripristino scrive gli stessi campi
  nella direzione opposta. File salvato come `backup.dat`.

## 7. Eventi camera → app (callback `SDKCallBackFunc`)

Due fonti native: il socket eventi 55741 (`CCameraEvent::ThreadProc`) e il polling di D212 (`CheckPropList`).

| Origine | Evento SDK (Java) | Significato |
|---|---|---|
| PTP 0xC004 | 30 CAPTURE_START | inizio scatto |
| PTP 0x400D (CaptureComplete) | 31 CAPTURE_COMPLETE | fine scatto |
| PTP 0xC001 | 32 POSTVIEW_COMPLETE | anteprima pronta (→ GetPostview) |
| PTP 0xC005 | 43 AFCAPTURE | esito AF |
| PTP 0x4008 (DeviceInfoChanged) | 41 DEVICEINFOCHANGED | proprietà disponibili cambiate |
| PTP 0x4002 (ObjectAdded) | 1 ADDIMAGE | nuovo file sulla scheda |
| D212: DF00 | 11 GET_CAMERA_MODE | cambio stato camera |
| D212: D222 | 12 IMAGE_VIEW | cambio numero oggetti |
| D212: D52F | 13 CAMERA_REQUEST_GPS | richiesta posizione |
| D212: D400/D401 | 17 REQUEST_REMOTE | richiesta remoto (probabile) |
| D212: DF41 | 47 RESERVED_OBJECT_COUNT | foto prenotate |
| D212: altre | 40 PROPVALUE (44/45 inizio/fine lista) | valore proprietà cambiato |
| SDK interno | 5/6, 53–56 | avanzamento / fine ricezione (anche > 2 e > 4 GiB) |
| SDK interno | 48/49/50 | invio (firmware) iniziato / in corso / finito |
| SDK interno | 7 CAMERA_DISCONNECT, 51 COMPLETE_XLOG_TRANSFER | |

## Limiti di questa analisi
- I significati di D220, D7B6, DE82, D801 e il formato dei pacchetti live view e di 0x9054 non sono decodificati.
- Le corrispondenze evento PTP → evento SDK sono lette dall'ordine dei confronti nel disassemblato; i nomi degli
  eventi vendor (0xC001–0xC006) non sono presenti nella libreria.
- I payload BLE sono ricavati dai serializzatori Java; la loro accettazione dipende dal modello e dal firmware.
