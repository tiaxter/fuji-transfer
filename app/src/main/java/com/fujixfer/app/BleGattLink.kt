package com.fujixfer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.fujixfer.core.FujiBle
import com.fujixfer.core.GattLink
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Blocking wrapper around BluetoothGatt: one operation at a time, each waiting for its callback.
 * Must not be used from the main thread.
 */
@SuppressLint("MissingPermission")
class BleGattLink(private val context: Context, private val device: BluetoothDevice) : GattLink {
    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val OP_TIMEOUT_MS = 10_000L
    }

    private class Result(val status: Int, val value: ByteArray?)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableFuture<Result>? = null
    private val connected = CompletableFuture<Unit>()
    private val discovered = CompletableFuture<Int>()
    private val mtu = CompletableFuture<Int>()
    private val chars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val notifications = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()

    @Volatile var isConnected = false
        private set

    val address: String get() = device.address

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            DebugLog.add("BLE stato collegamento: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                val e = IOException("Bluetooth disconnesso dalla camera (codice $status)")
                connected.completeExceptionally(e)
                discovered.completeExceptionally(e)
                mtu.complete(0)
                pending?.completeExceptionally(e)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            DebugLog.add("BLE MTU=$value status=$status")
            mtu.complete(value)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status)
        }

        @Deprecated("Android < 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish(status, ch.value)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            finish(status, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            finish(status, null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            finish(status, null)
        }

        @Deprecated("Android < 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) deliver(ch.uuid, ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(ch.uuid, value)
        }
    }

    private fun finish(status: Int, value: ByteArray?) {
        pending?.complete(Result(status, value))
    }

    /** Called for every notification (on the Bluetooth thread), with the upper-case characteristic UUID. */
    @Volatile var onNotify: ((String, ByteArray) -> Unit)? = null

    private fun deliver(uuid: UUID, value: ByteArray) {
        DebugLog.add("BLE notifica ${DebugLog.short(uuid.toString())} = ${DebugLog.hex(value)}")
        queueFor(key(uuid)).offer(value.clone())
        onNotify?.invoke(key(uuid), value)
    }

    private fun queueFor(k: String) = notifications.getOrPut(k) { LinkedBlockingQueue() }

    private fun key(uuid: UUID) = uuid.toString().uppercase(Locale.ROOT)

    /** Connects, raises the MTU like XApp (185) and discovers the services. */
    fun connect(timeoutMs: Long = 15_000) {
        DebugLog.add("BLE connessione a ${device.name} ${device.address} (bond=${device.bondState}, tipo=${device.type})")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Impossibile aprire la connessione Bluetooth")
        gatt = g
        await(connected, timeoutMs, "Camera non raggiungibile via Bluetooth")
        // XApp's order: MTU, then services, no connection priority. With CONNECTION_PRIORITY_HIGH requested here
        // (4 Nov to 13 Nov) the MTU answer came ~4.5 s after the link on every X-M5 connection, and the GATT requests
        // that followed still took 50-60 ms each, so the shorter interval gave nothing.
        val t0 = System.currentTimeMillis()
        if (g.requestMtu(FujiBle.MTU)) {
            try {
                mtu.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // keep the default MTU
            }
        }
        DebugLog.detail("BLE MTU in ${System.currentTimeMillis() - t0} ms")
        if (!g.discoverServices()) throw IOException("Ricerca dei servizi Bluetooth non avviata")
        val status = await(discovered, 15_000, "La camera non ha elencato i servizi Bluetooth")
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattLink.GattException("Servizi Bluetooth", status)
        for (s in g.services) {
            DebugLog.add("BLE servizio ${key(s.uuid)}: " + s.characteristics.joinToString(" ") {
                DebugLog.short(it.uuid.toString()) + "/" + it.properties.toString(16)
            })
            for (c in s.characteristics) chars.putIfAbsent(key(c.uuid), c)
        }
    }

    private fun <T> await(f: CompletableFuture<T>, ms: Long, timeoutMessage: String): T {
        try {
            return f.get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException(timeoutMessage)
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: timeoutMessage)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrotto")
        }
    }

    /**
     * The characteristic inside the given service. The same UUID can exist in two services on one body
     * (legacy and RED): only when the service is missing altogether do we fall back to any instance.
     */
    private fun find(service: String, uuid: String): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        val svc = g.getService(UUID.fromString(service))
        if (svc != null) return svc.getCharacteristic(UUID.fromString(uuid))
        return chars[uuid.uppercase(Locale.ROOT)]
    }

    private fun characteristic(service: String, uuid: String): BluetoothGattCharacteristic =
        find(service, uuid) ?: throw IOException("Caratteristica Bluetooth assente: ${DebugLog.short(uuid)}")

    /** Runs one GATT request and waits for its callback; retries a few times if the stack is busy. */
    private fun gattOp(what: String, start: (BluetoothGatt) -> Boolean): ByteArray? {
        val g = gatt ?: throw IOException("Bluetooth non connesso")
        if (!isConnected) throw IOException("Bluetooth disconnesso dalla camera")
        var f: CompletableFuture<Result>? = null
        for (attempt in 1..5) {
            val candidate = CompletableFuture<Result>()
            pending = candidate
            if (start(g)) {
                f = candidate
                break
            }
            pending = null
            Thread.sleep(150)
        }
        val started = f ?: throw IOException("$what: richiesta Bluetooth rifiutata dal telefono")
        try {
            val r = await(started, OP_TIMEOUT_MS, "$what: la camera non risponde via Bluetooth")
            if (r.status != BluetoothGatt.GATT_SUCCESS) {
                DebugLog.add("BLE $what -> stato GATT ${r.status}")
                throw GattLink.GattException(what, r.status)
            }
            return r.value
        } finally {
            pending = null
        }
    }

    override fun has(service: String, characteristic: String): Boolean = find(service, characteristic) != null

    override fun read(service: String, characteristic: String): ByteArray {
        val c = characteristic(service, characteristic)
        return gattOp("Lettura ${DebugLog.short(characteristic)}") { it.readCharacteristic(c) } ?: ByteArray(0)
    }

    @Suppress("DEPRECATION")
    override fun write(service: String, characteristic: String, value: ByteArray) {
        val c = characteristic(service, characteristic)
        gattOp("Scrittura ${DebugLog.short(characteristic)} in ${DebugLog.short(service)}") { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.setValue(value)
                g.writeCharacteristic(c)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun enableNotifications(service: String, characteristic: String) {
        val c = characteristic(service, characteristic)
        val g = gatt ?: throw IOException("Bluetooth non connesso")
        queueFor(characteristic.uppercase(Locale.ROOT)).clear()
        if (!g.setCharacteristicNotification(c, true)) throw IOException("Notifiche Bluetooth non attivabili")
        val d = c.getDescriptor(CCCD) ?: return
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        gattOp("Notifiche Bluetooth") { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            } else {
                d.setValue(v)
                gg.writeDescriptor(d)
            }
        }
    }

    override fun awaitNotification(characteristic: String, timeoutMs: Long): ByteArray? {
        if (!isConnected) throw IOException("Bluetooth disconnesso dalla camera")
        return try {
            queueFor(characteristic.uppercase(Locale.ROOT)).poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrotto")
        }
    }

    override fun isBonded(): Boolean = device.bondState == BluetoothDevice.BOND_BONDED

    override fun requestBond() {
        DebugLog.add("BLE richiesta bonding (stato attuale ${device.bondState})")
        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
    }

    override fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrotto")
        }
    }

    /** Back to the balanced connection interval (the default) after lowPower(), e.g. after a download. */
    fun relaxPriority() = setPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)

    /**
     * Longest connection interval (about 100-125 ms): while photos go over Wi-Fi this link only carries the camera's
     * state notifications. Bluetooth and Wi-Fi share the 2.4 GHz band, on the phone and on the camera.
     */
    fun lowPower() = setPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)

    private fun setPriority(priority: Int) {
        val g = gatt ?: return
        if (!isConnected) return
        try {
            g.requestConnectionPriority(priority)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile: the link stays as it is
        }
    }

    fun close() {
        if (gatt != null) DebugLog.add("BLE chiudo il collegamento con ${device.address}")
        isConnected = false
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
        } catch (e: Exception) {
            // already gone
        }
        g.close()
    }
}
