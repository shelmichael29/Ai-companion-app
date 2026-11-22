package com.aicarcompanion.app.diagnostics.bluetooth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/**
 * Run a blocking socket connect on IO, but keep the caller free to cancel it.
 * A timeout or cancelled parent closes the socket before waiting for the worker to exit.
 * Closing the socket interrupts Android BluetoothSocket.connect().
 */
internal suspend fun blockingConnectWithCancellation(
    timeoutMillis: Long,
    abort: () -> Unit,
    connect: () -> Unit,
) = supervisorScope {
    val worker = async(Dispatchers.IO) { connect() }
    try {
        withTimeout(timeoutMillis.coerceAtLeast(1)) { worker.await() }
    } finally {
        if (!worker.isCompleted) abort()
    }
}
