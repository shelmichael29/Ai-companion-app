package com.aicarcompanion.app.diagnostics.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.ElmLinkFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

enum class BluetoothAvailability { READY, NO_PERMISSION, DISABLED, NOT_SUPPORTED }

/**
 * Android Bluetooth access for OBD-II adapters. We only use devices the user has already
 * paired in Android settings, so no scanning (and no location permission) is needed.
 */
class BluetoothGateway(private val context: Context) : ElmLinkFactory {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** The runtime permission needed on this Android version, or null if none. */
    val requiredPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasPermission(): Boolean = requiredPermission?.let {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } ?: true

    fun availability(): BluetoothAvailability {
        val a = adapter ?: return BluetoothAvailability.NOT_SUPPORTED
        if (!hasPermission()) return BluetoothAvailability.NO_PERMISSION
        return if (a.isEnabled) BluetoothAvailability.READY else BluetoothAvailability.DISABLED
    }

    @SuppressLint("MissingPermission") // Checked by availability().
    fun pairedDevices(): List<AdapterDevice> {
        if (availability() != BluetoothAvailability.READY) return emptyList()
        return adapter?.bondedDevices.orEmpty().map { d ->
            AdapterDevice(
                name = d.name?.takeIf { it.isNotBlank() } ?: d.address,
                address = d.address,
                radio = when (d.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> AdapterRadio.CLASSIC
                    BluetoothDevice.DEVICE_TYPE_DUAL -> AdapterRadio.DUAL
                    BluetoothDevice.DEVICE_TYPE_LE -> AdapterRadio.LE_ONLY
                    else -> AdapterRadio.UNKNOWN
                },
            )
        }.sortedWith(compareByDescending<AdapterDevice> { it.looksLikeObdAdapter }.thenBy { it.name.lowercase() })
    }

    fun findPaired(address: String): AdapterDevice? = pairedDevices().firstOrNull { it.address == address }

    /** Opens an RFCOMM (Serial Port Profile) socket; tries secure, then insecure, which many clones need. */
    @SuppressLint("MissingPermission")
    override suspend fun open(device: AdapterDevice): ElmLink = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw IOException("Bluetooth permission not granted")
        val remote = adapter?.getRemoteDevice(device.address) ?: throw IOException("Bluetooth not available")
        val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
            { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var lastError: IOException? = null
        for (create in attempts) {
            val socket = try { create(remote) } catch (e: IOException) { lastError = e; continue }
            try {
                socket.connect()
                return@withContext StreamElmLink(socket.inputStream, socket.outputStream) { runCatching { socket.close() } }
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw lastError ?: IOException("Couldn't open a Bluetooth connection")
    }

    companion object {
        /** Standard Serial Port Profile UUID used by ELM327 Bluetooth Classic adapters. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
