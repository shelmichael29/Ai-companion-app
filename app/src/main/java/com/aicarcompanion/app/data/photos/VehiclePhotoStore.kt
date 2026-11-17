package com.aicarcompanion.app.data.photos

import androidx.core.graphics.scale
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User-provided vehicle photos. A picked image is downscaled and copied into private app
 * storage, so no storage permission or long-lived URI grant is needed. No stock images are
 * bundled (licensing), so cars without a photo use an illustrated placeholder.
 */
class VehiclePhotoStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "vehicle_photos").apply { mkdirs() }
    private val resolver = context.applicationContext.contentResolver

    /** Bumped whenever a photo changes so the UI reloads. */
    private val _versions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val versions: StateFlow<Map<String, Int>> = _versions.asStateFlow()

    private fun file(vehicleId: String) = File(dir, "${vehicleId.filter { it.isLetterOrDigit() || it == '-' }}.jpg")

    suspend fun save(vehicleId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
            file(vehicleId).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            _versions.update { it + (vehicleId to (it[vehicleId] ?: 0) + 1) }
            true
        }.getOrDefault(false)
    }

    suspend fun load(vehicleId: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(vehicleId)
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    fun delete(vehicleId: String) {
        file(vehicleId).delete()
        _versions.update { it + (vehicleId to (it[vehicleId] ?: 0) + 1) }
    }

    private companion object { const val MAX_EDGE = 1280 }
}

/** Loads a vehicle's photo off the main thread and reloads when it changes. */
@androidx.compose.runtime.Composable
fun rememberVehiclePhoto(store: VehiclePhotoStore, vehicleId: String?, version: Int?): Bitmap? {
    val photo = androidx.compose.runtime.remember(vehicleId) { androidx.compose.runtime.mutableStateOf<Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(vehicleId, version) {
        photo.value = vehicleId?.let { store.load(it) }
    }
    return photo.value
}
