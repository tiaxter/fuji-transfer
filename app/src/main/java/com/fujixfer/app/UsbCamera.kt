package com.fujixfer.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.fujixfer.core.PacketLink
import com.fujixfer.core.PtpSession
import java.io.IOException

/** PTP over USB bulk, fed into the same PtpSession/TransferEngine used for Wi-Fi. */
object UsbCamera {
    const val FUJI_VENDOR_ID = 0x04CB

    // Android's usbfs handles 16 KiB per bulkTransfer reliably on every kernel.
    private const val BULK_CHUNK = 16 * 1024
    private const val TIMEOUT_MS = 10_000

    fun findDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == FUJI_VENDOR_ID }
            ?: usb.deviceList.values.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } }

    fun findPtpInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

    fun hasMassStorageOnly(device: UsbDevice): Boolean =
        findPtpInterface(device) == null &&
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }

    /** Opens the device and a PTP session. [afterFailure] triggers a PTP Device Reset first. */
    fun openSession(usb: UsbManager, device: UsbDevice, afterFailure: Boolean): PtpSession {
        val iface = findPtpInterface(device)
            ?: throw IOException(
                if (hasMassStorageOnly(device))
                    "La camera è in modalità lettore di schede (mass storage). Cambia il modo USB sulla camera."
                else "Nessuna interfaccia PTP sul dispositivo USB"
            )
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val e = iface.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
        }
        if (inEp == null || outEp == null) throw IOException("Endpoint bulk PTP non trovati")

        val conn = usb.openDevice(device) ?: throw IOException("Impossibile aprire il dispositivo USB (permesso?)")
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IOException("Impossibile reclamare l'interfaccia PTP")
        }
        if (afterFailure) {
            // PTP class request 0x66 = Device Reset; best effort, some bodies stall on it.
            conn.controlTransfer(0x21, 0x66, 0, iface.id, null, 0, 1000)
        }
        val link = PacketLink(UsbBulk(conn, iface, inEp, outEp), BULK_CHUNK, TIMEOUT_MS, TIMEOUT_MS)
        val session = PtpSession(link)
        try {
            session.openSession()
        } catch (e: Exception) {
            link.close()
            throw e
        }
        return session
    }

    private class UsbBulk(
        private val conn: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val inEp: UsbEndpoint,
        private val outEp: UsbEndpoint,
    ) : PacketLink.Bulk {
        override fun read(buf: ByteArray, maxLen: Int, timeoutMs: Int): Int {
            val n = conn.bulkTransfer(inEp, buf, minOf(maxLen, BULK_CHUNK), timeoutMs)
            if (n < 0) throw IOException("USB bulk-in fallito o in timeout")
            return n
        }

        override fun write(buf: ByteArray, off: Int, len: Int, timeoutMs: Int) {
            var pos = off
            var left = len
            while (left > 0) {
                val n = conn.bulkTransfer(outEp, buf, pos, minOf(left, BULK_CHUNK), timeoutMs)
                if (n <= 0) throw IOException("USB bulk-out fallito")
                pos += n
                left -= n
            }
        }

        override fun close() {
            try {
                conn.releaseInterface(iface)
            } finally {
                conn.close()
            }
        }
    }
}
