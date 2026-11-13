# Metadati e dimensioni dei file per la galleria: cosa provare sulla camera

Stato: da provare su una camera reale (X-M5). Nulla di quanto segue è implementato.
Fonte: decompilazione di XApp 2.7.6 (vedi `XAPP_PROTOCOLLO.md`) e il codice attuale di `core/`.

## Situazione attuale
- **EXIF** (simulazione pellicola, posizione, orientamento): `TransferEngine.readExif` legge i primi byte di ogni
  file (`exifAt`, GetPartialObject da offset 0; per i RAF anche il JPEG incluso). `TransferService` lo fa a gruppi
  di 3–4 scatti con pause, perché il 3 novembre l'X-M5 ha spento il Wi-Fi dopo 10 s di un gruppo da 12.
- **Dimensioni**: in modalità galleria (0xD226 = 0) ObjectInfo riporta dimensioni fittizie. `ObjectLister.listFujiWifi`
  in modo XApp elenca senza dimensioni reali (scelta voluta: con 0xD226 = 2 ogni ObjectInfo costava ~250 ms
  contro 56 ms e dopo circa un minuto la camera spegneva il Wi-Fi). La dimensione vera arriva solo al download
  (`sizeForDownload`, `withRealSize`).

## Cosa fa XApp
- Non mostra pellicola né GPS nella galleria: non legge l'EXIF per l'elenco.
- Per ogni foto usa **0x9054 GetExtensionObjectInfo** (`ControlFFIR.getExtensionObjectInfo(handle, "")`), che
  restituisce `ImageInformation`: dimensione del file, formato e formato reale, **orientamento**, larghezza/altezza
  in pixel, data di scatto, nome interno, oggetto padre, dimensione/formato/pixel della miniatura.
- La dimensione la legge foto per foto solo all'import (`ReceiveImageModel`, dopo `setImageForceCompression`).
  0xD227 (ImageCompressionRealInfo) in modo Wi-Fi XApp la imposta solo a 0.
- Nelle librerie native c'è **MTP GetObjectPropList 0x9805** (`FTL_MTP_GetObjectPropList`, 5 parametri, dati
  camera → app), ma non ho trovato chiamate dal Java per la galleria.

## Non esiste un comando "tutti gli EXIF"
L'EXIF sta dentro il file; nessun opcode PTP o Fuji trovato nell'APK lo restituisce in blocco. Pellicola e GPS
richiedono comunque una lettura per file. Si può alleggerire solo il resto (orientamento, dimensioni, data).

## Prove da fare (una volta, alla connessione, solo log)
In modalità galleria (0xD226 = 0), senza toccare il resto della sessione:

1. **0x9805 GetObjectPropList** con parametri {handle 0xFFFFFFFF, formato 0, proprietà 0xDC04 (ObjectSize),
   gruppo 0, profondità 0}. Annotare: risposta (OK / OperationNotSupported 0x2005 / altro), tempo, numero di
   elementi, e se le dimensioni coincidono con quelle reali di 2–3 file (lette dopo con 0xD226 = 2).
   Se accettato, provare anche 0xDC08 (data di creazione) e 0xDC07 (nome file), e 0xFFFFFFFF (tutte le proprietà)
   per vedere se la camera aggiunge proprietà Fuji (es. 0xD802, dimensione a 64 bit).
2. **0x9054 GetExtensionObjectInfo** sulla prima foto: tempo, e se dimensione e orientamento sono quelli veri
   (confronto con ObjectInfo in 0xD226 = 2 e con l'EXIF). Formato della risposta da decodificare: salvare i byte
   grezzi nel log. Poi su 20 handle in fila (`transactPipelined`) per misurare il tempo per foto.
3. Controllare dopo ogni prova che la camera resti collegata (nessun AP_STATE = 0x8000 via Bluetooth).

## Decisione in base ai risultati
| Risultato | Cosa fare |
|---|---|
| 0x9805 accettato, dimensioni vere | dimensioni (ed eventualmente data/nome) di tutti i file in una richiesta, già nell'elenco |
| solo 0x9054, valori veri | dimensioni e orientamento in fila durante l'elenco; l'EXIF serve solo per pellicola e GPS |
| 0x9054 vero solo per l'orientamento | usarlo al posto dell'EXIF per l'orientamento |
| nessuno dei due | lasciare com'è; al massimo dimensioni calcolate in background quando la camera è libera |

## Note
- Tenere le prove lontane dal download e dalle miniature: su questa camera i picchi di comandi hanno già causato lo
  spegnimento del Wi-Fi.
- Se 0x9805 o 0x9054 rispondono "occupata" (0x2019), riprovare una volta dopo 500 ms, non di più.
