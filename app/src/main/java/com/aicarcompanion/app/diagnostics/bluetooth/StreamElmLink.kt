package com.aicarcompanion.app.diagnostics.bluetooth

import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.ObdTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * [ElmLink] over plain streams (a Bluetooth RFCOMM socket, or a TCP socket for Wi-Fi adapters).
 * Uses available()-polling so reads can time out without blocking a thread forever.
 */
class StreamElmLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : ElmLink {

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        // Discard stale bytes (e.g. a late answer to a timed-out command) before sending.
        while (input.available() > 0) input.read(ByteArray(input.available()))
        output.write(text.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String = withContext(Dispatchers.IO) {
        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMillis
        val chunk = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(chunk, 0, minOf(available, chunk.size))
                if (n < 0) throw java.io.IOException("Adapter closed the connection")
                buffer.append(String(chunk, 0, n, Charsets.US_ASCII))
                if (buffer.contains('>')) return@withContext buffer.toString()
            } else {
                delay(10)
            }
        }
        throw ObdTimeoutException("No response from adapter within ${timeoutMillis}ms")
    }

    override fun close() = onClose()
}
