# Protocolli Fujifilm X/GFX — note preliminari (da fonti pubbliche, da verificare con l'APK)

Fonti: libgphoto2 (camlibs/ptp2/fujiptpip.c, ptp.h), hkr/fuji-cam-wifi-tool, malc0mn/ptp-ip.

## USB (cavo)
- Classe PTP (Still Image, interfaccia classe 0x06 sottoclasse 0x01), vendor ID 0x04CB.
- Protocollo: PTP su USB Bulk (container: len32, type16, code16, transid32, payload).
- Download oggetti: GetObjectHandles / GetObjectInfo / GetObject (o GetPartialObject per riprendere).
- Nota: Fuji riusa gli stessi product ID tra modelli diversi; in modalità "USB RAW CONV./BACKUP RESTORE" o "PC AUTO SAVE" la camera non espone PTP standard.

## Wi-Fi
- La camera fa da access point (SSID tipo FUJIFILM-X-T1-XXXX), IP camera 192.168.0.1.
- Tre socket TCP:
  - 55740 comandi/dati (PTP "alla Fuji")
  - 55741 eventi (si apre dopo InitiateOpenCapture)
  - 55742 stream JPEG live view (idem)
- Handshake iniziale (stile PTP/IP, ma Fuji): pacchetto INIT_COMMAND_REQUEST di lunghezza FISSA 82 byte
  (len32, type32=1, magic 0x8f53e4f2, GUID 16 byte, nome host UCS-2 LE, zero-padded).
  Con lunghezze diverse la camera (X-T4) chiude il socket.
  La prima volta la camera chiede conferma sul display (pairing).
- Dopo l'init il formato NON è PTP/IP standard ma simile a USB bulk:
  <len32> <type16: 1=cmd 2=data 3=resp 4=event> <code16> <transid32> <payload>
- Opcodes Fuji: 0x902B GetDeviceInfo (Fuji), 0x9020/21 movie, 0x9022 preview,
  0x9040-0x9042 FmSendObjectInfo/SendObject/SendPartialObject.
- Eventi: 0xC001 PreviewAvailable, 0xC004 ObjectAdded.
- X-T4: GetDeviceInfo standard ritorna vuoto, serve quello Fuji.

## Fonti probabili di instabilità lato Android (da confermare)
- Wi-Fi senza internet: Android scarta la rete o instrada su dati mobili -> serve legare i socket alla rete della camera.
- Timeout/keep-alive troppo corti, nessun resume dei download, nessuna riconnessione automatica.
