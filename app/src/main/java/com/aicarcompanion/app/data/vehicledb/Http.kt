package com.aicarcompanion.app.data.vehicledb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP abstraction so providers can be unit-tested with canned responses. */
fun interface HttpClient {
    /** @throws IOException when the network is unavailable or the request fails to complete. */
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

/**
 * Sends values in the request body instead of the address, so they never appear in a URL (and so
 * not in server or proxy access logs). Used for the VIN.
 */
fun interface HttpFormPoster {
    /** @throws IOException when the network is unavailable or the request fails to complete. */
    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse
}

/** [HttpClient] and [HttpFormPoster] built on the platform's HttpURLConnection; no extra dependencies. */
class UrlConnectionHttpClient(private val timeoutMillis: Int = 15_000) : HttpClient, HttpFormPoster {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = open(url, headers)
            try {
                read(connection)
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = open(url, headers)
            try {
                val payload = form.entries.joinToString("&") { "${it.key.urlEncoded()}=${it.value.urlEncoded()}" }.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
                read(connection)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("User-Agent", "AiCarCompanion-Android")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        return connection
    }

    private fun read(connection: HttpURLConnection): HttpResponse {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    }
}

internal fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/** Persisted copies of previous responses, enabling offline vehicle selection. */
interface ResponseCache {
    suspend fun get(key: String): CachedResponse?
    suspend fun put(key: String, body: String, fetchedAtMillis: Long)
}

data class CachedResponse(val body: String, val fetchedAtMillis: Long)

class InMemoryResponseCache : ResponseCache {
    private val map = mutableMapOf<String, CachedResponse>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, body: String, fetchedAtMillis: Long) {
        map[key] = CachedResponse(body, fetchedAtMillis)
    }
}

sealed interface LookupResult<out T> {
    data class Success<T>(val value: T, val fromCache: Boolean = false) : LookupResult<T>
    data class Failure(val error: LookupError) : LookupResult<Nothing>
}

enum class LookupError(val userMessage: String) {
    OFFLINE("Can't reach the vehicle database. Check your connection, or enter details manually."),
    SERVICE("The vehicle database returned an error. Try again later, or enter details manually."),
    MALFORMED("The vehicle database sent an unexpected response. Enter details manually."),
}

inline fun <T, R> LookupResult<T>.map(transform: (T) -> R): LookupResult<R> = when (this) {
    is LookupResult.Success -> LookupResult.Success(transform(value), fromCache)
    is LookupResult.Failure -> this
}

/**
 * Fetches [url] using a cache-first strategy: fresh cache entries are used directly; otherwise
 * the network is tried and, if it fails, any stale cached copy is returned instead.
 */
internal class CachingFetcher(
    private val http: HttpClient,
    private val cache: ResponseCache,
    private val maxAgeMillis: Long,
    private val now: () -> Long,
) {
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): LookupResult<String> {
        val cached = cache.get(url)
        if (cached != null && now() - cached.fetchedAtMillis < maxAgeMillis) {
            return LookupResult.Success(cached.body, fromCache = true)
        }
        val response = try {
            http.get(url, headers)
        } catch (e: IOException) {
            return cached?.let { LookupResult.Success(it.body, fromCache = true) }
                ?: LookupResult.Failure(LookupError.OFFLINE)
        }
        if (response.code !in 200..299) {
            return cached?.let { LookupResult.Success(it.body, fromCache = true) }
                ?: LookupResult.Failure(LookupError.SERVICE)
        }
        cache.put(url, response.body, now())
        return LookupResult.Success(response.body)
    }
}
