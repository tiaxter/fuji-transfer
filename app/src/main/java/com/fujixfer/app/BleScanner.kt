package com.fujixfer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.fujixfer.core.FujiBle
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Finds a Fuji body: first among devices the phone already knows (a camera held open by XApp stops
 * advertising, but its connection can be shared), then by advertisement like XApp (Fuji services or
 * company id 0x04D8), with a second unfiltered pass for phones whose hardware filters drop the adverts.
 */
@SuppressLint("MissingPermission")
object BleScanner {
    class Found(val device: BluetoothDevice, val advert: FujiBle.Advert)

    private val FUJI_NAME = Regex(
        "(?i)(fuji|x-?t\\d|x-?s\\d|x-?e\\d|x-?h\\d|x-?m\\d|x-?pro|x-?a\\d|x100|x-?half|xf\\d|gfx|x30)",
    )

    fun looksLikeFuji(name: String?) = name != null && FUJI_NAME.containsMatchIn(name)

    private fun adapter(context: Context) = (context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw IOException("Questo telefono non ha il Bluetooth")).also {
        if (!it.isEnabled) throw IOException("Attiva il Bluetooth del telefono e riprova")
    }

    /**
     * Devices already connected (GATT) or bonded that could be the camera: the stored address first,
     * then connected ones, then bonded ones whose name looks like a Fuji body.
     */
    fun knownCandidates(context: Context, storedAddress: String?): List<BluetoothDevice> {
        val adapter = adapter(context)
        val bm = context.getSystemService(BluetoothManager::class.java)
        val connected = try {
            bm?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val bonded = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        DebugLog.add("BLE collegati: " + connected.joinToString { "${it.name} ${it.address}" }.ifEmpty { "nessuno" })
        DebugLog.add("BLE abbinati: " + bonded.joinToString { "${it.name} ${it.address} t${it.type}" }.ifEmpty { "nessuno" })
        val connectedAddrs = connected.map { it.address }.toSet()
        return (connected + bonded)
            .distinctBy { it.address }
            .filter { it.address.equals(storedAddress, ignoreCase = true) || looksLikeFuji(it.name) }
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .sortedBy {
                when {
                    it.address.equals(storedAddress, ignoreCase = true) -> 0
                    it.address in connectedAddrs -> 1
                    else -> 2
                }
            }
    }

    fun isConnected(context: Context, device: BluetoothDevice): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)
            ?.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
    } catch (e: Exception) {
        false
    }

    fun find(context: Context, timeoutMs: Long, accept: (FujiBle.Advert) -> Boolean): Found? {
        val scanner = adapter(context).bluetoothLeScanner ?: throw IOException("Scansione Bluetooth non disponibile")
        val filtered = FujiBle.SCAN_SERVICES.map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() } +
            ScanFilter.Builder().setManufacturerData(FujiBle.MANUFACTURER_ID, ByteArray(0)).build()
        val first = minOf(timeoutMs, 10_000L)
        return scanOnce(scanner, filtered, first, "con filtri", accept)
            ?: if (timeoutMs > first) scanOnce(scanner, null, timeoutMs - first, "senza filtri", accept) else null
    }

    private fun scanOnce(
        scanner: BluetoothLeScanner,
        filters: List<ScanFilter>?,
        timeoutMs: Long,
        label: String,
        accept: (FujiBle.Advert) -> Boolean,
    ): Found? {
        val result = CompletableFuture<Found>()
        val seen = HashSet<String>()

        fun handle(r: ScanResult) {
            val rec = r.scanRecord ?: return
            val name = r.device.name ?: rec.deviceName
            val md = rec.getManufacturerSpecificData(FujiBle.MANUFACTURER_ID)
            val uuids = rec.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
            val adv = FujiBle.Advert.parse(name, r.device.address, md, uuids)
            val interesting = adv != null || md != null || looksLikeFuji(name)
            if (interesting && seen.add(r.device.address)) {
                DebugLog.add(
                    "BLE visto $name ${r.device.address} rssi=${r.rssi} fuji=${DebugLog.hex(md)} servizi=" +
                        uuids.joinToString(",") { DebugLog.short(it) } +
                        (if (adv == null) " -> ignorato" else " -> ${if (adv.red) "RED" else "legacy"}" +
                            (if (adv.pairingMode) " in abbinamento" else "")),
                )
            }
            if (adv != null && accept(adv)) result.complete(Found(r.device, adv))
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }

            override fun onScanFailed(errorCode: Int) {
                result.completeExceptionally(IOException("Scansione Bluetooth fallita (codice $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        DebugLog.add("BLE ricerca $label (${timeoutMs / 1000} s)")
        scanner.startScan(filters, settings, cb)
        try {
            return result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            DebugLog.add("BLE ricerca $label finita: ${seen.size} dispositivi Fuji visti, nessuno adatto")
            return null
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Scansione Bluetooth fallita")
        } finally {
            try {
                scanner.stopScan(cb)
            } catch (e: Exception) {
                // adapter turned off meanwhile
            }
        }
    }
}
