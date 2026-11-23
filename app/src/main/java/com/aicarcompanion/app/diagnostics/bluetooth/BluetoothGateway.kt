package com.aicarcompanion.app.diagnostics.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class BluetoothAvailability { READY, NO_PERMISSION, DISABLED, NOT_SUPPORTED }

/**
 * Android Bluetooth Classic access for OBD-II (ELM327) adapters: availability, paired devices,
 * time-limited discovery of nearby adapters, and RFCOMM connections with a hard timeout.
 *
 * Permissions:
 * - **Android 12+:** BLUETOOTH_CONNECT and BLUETOOTH_SCAN, declared "never for location".
 * - **Android 8–11:** location is also needed for discovery, a platform rule.
 *   Pairing with a discovered adapter is Android's own pairing prompt; the PIN is usually 1234 or 0000.
 */
class BluetoothGateway(
    private val context: Context,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) : AdapterTransport {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override val requiredPermissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Android asks for both; discovery needs fine location before Android 12.
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The permission needed just to use paired adapters (no discovery), or null if none. */
    val requiredPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(): Boolean = requiredPermission?.let(::granted) ?: true

    /** Discovery needs extra permission (scan, or location before Android 12). */
    override fun canDiscover(): Boolean = requiredPermissions.all(::granted)

    override fun availability(): BluetoothAvailability {
        val a = adapter ?: return BluetoothAvailability.NOT_SUPPORTED
        if (!hasPermission()) return BluetoothAvailability.NO_PERMISSION
        return if (a.isEnabled) BluetoothAvailability.READY else BluetoothAvailability.DISABLED
    }

    @SuppressLint("MissingPermission") // Checked by availability().
    override fun pairedDevices(): List<AdapterDevice> {
        if (availability() != BluetoothAvailability.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty().map(::toAdapterDevice)
            .sortedWith(compareByDescending<AdapterDevice> { it.looksLikeObdAdapter }.thenBy { it.name.lowercase() })
    }

    fun findPaired(address: String): AdapterDevice? = pairedDevices().firstOrNull { it.address == address }

    @SuppressLint("MissingPermission") // Checked by canDiscover().
    override fun discover(timeoutMillis: Long): Flow<AdapterDevice> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled || !hasPermission() || !canDiscover()) {
            close()
            return@callbackFlow
        }
        val seen = mutableSetOf<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = deviceFrom(intent) ?: return
                        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return // ELM327 Classic adapters only
                        if (seen.add(device.address)) trySend(toAdapterDevice(device))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (a.isDiscovering) a.cancelDiscovery()
        if (!a.startDiscovery()) close()
        val timer = launch { delay(timeoutMillis); close() }
        awaitClose {
            timer.cancel()
            runCatching { a.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.flowOn(Dispatchers.Main)

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun toAdapterDevice(d: BluetoothDevice) = AdapterDevice(
        // Never fall back to the hardware address: names are shown to Everyday users.
        name = d.name?.takeIf { it.isNotBlank() } ?: UNNAMED_DEVICE,
        address = d.address,
        radio = when (d.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> AdapterRadio.CLASSIC
            BluetoothDevice.DEVICE_TYPE_DUAL -> AdapterRadio.DUAL
            BluetoothDevice.DEVICE_TYPE_LE -> AdapterRadio.LE_ONLY
            else -> AdapterRadio.UNKNOWN
        },
    )

    /**
     * Opens an RFCOMM (Serial Port Profile) socket: secure first, then insecure, which many
     * clones need. Each attempt is limited to [connectTimeoutMillis]; a stuck attempt is aborted
     * by closing its socket. Failures are reported as [AdapterConnectException] with a reason.
     */
    @SuppressLint("MissingPermission")
    override suspend fun open(device: AdapterDevice): ElmLink = withContext(Dispatchers.IO) {
        val a = adapter ?: throw AdapterConnectException(ConnectFailure.NOT_SUPPORTED)
        if (!hasPermission()) throw AdapterConnectException(ConnectFailure.PERMISSION_DENIED)
        if (!a.isEnabled) throw AdapterConnectException(ConnectFailure.BLUETOOTH_OFF)
        // Discovery slows connections down a lot; never connect while searching.
        if (canDiscover()) runCatching { a.cancelDiscovery() }
        val remote = a.getRemoteDevice(device.address)
        val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var last: AdapterConnectException? = null
        for (create in attempts) {
            val socket = try { create(remote) } catch (e: IOException) {
                last = AdapterConnectException(ConnectFailure.ADAPTER_UNAVAILABLE, e.message); continue
            }
            var handedOff = false
            try {
                blockingConnectWithCancellation(connectTimeoutMillis, { runCatching { socket.close() } }) {
                    socket.connect()
                }
                // Cancellation between connect and handing the stream to the caller still
                // closes the socket instead of leaving a hidden Bluetooth session.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val link = StreamElmLink(socket.inputStream, socket.outputStream) {
                    runCatching { socket.close() }
                }
                handedOff = true
                return@withContext link
            } catch (e: TimeoutCancellationException) {
                last = AdapterConnectException(ConnectFailure.TIMEOUT, "Bluetooth connection timed out")
            } catch (e: CancellationException) {
                throw e // Explicit cancel must never trigger the insecure fallback.
            } catch (e: IOException) {
                last = AdapterConnectException(ConnectFailureClassifier.classify(e), e.message)
            } finally {
                if (!handedOff) runCatching { socket.close() }
            }
        }
        throw last ?: AdapterConnectException(ConnectFailure.UNKNOWN)
    }

    companion object {
        /** Standard Serial Port Profile UUID used by ELM327 Bluetooth Classic adapters. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 12_000L
        const val UNNAMED_DEVICE = "Unnamed Bluetooth device"
    }
}
