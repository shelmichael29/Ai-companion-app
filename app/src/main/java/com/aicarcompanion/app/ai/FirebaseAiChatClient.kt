package com.aicarcompanion.app.ai

import android.content.Context
import android.util.Log
import com.aicarcompanion.app.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Gemini through Firebase AI Logic (Gemini Developer API). The Gemini API key lives only in the
 * Firebase project; requests carry an App Check token (limited-use, for replay protection).
 */
class FirebaseAiChatClient(
    private val context: Context,
) : AiChatClient {

    override val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    @Volatile private var thinkingSupported = true

    override val modelName: String
        get() = if (!isConfigured) ModelSelection.DEFAULT_MODEL else runCatching {
            ModelSelection.choose(FirebaseRemoteConfig.getInstance().getString(ModelSelection.REMOTE_CONFIG_KEY))
        }.getOrDefault(ModelSelection.DEFAULT_MODEL)

    /**
     * How long one Gemini answer may take ([AiTimeouts]): Remote Config `penelope_ai_timeout_ms`,
     * clamped to 6–30 s, default 12 s. Adjustable without a new build if the logged durations
     * show normal answers need longer.
     */
    val requestTimeoutMillis: Long
        get() = if (!isConfigured) AiTimeouts.DEFAULT_MILLIS else runCatching {
            AiTimeouts.choose(FirebaseRemoteConfig.getInstance().getLong(AiTimeouts.REMOTE_CONFIG_KEY))
        }.getOrDefault(AiTimeouts.DEFAULT_MILLIS)

    /**
     * A new Gemini model object is created for every request, so no session state can go stale;
     * reset only re-enables optional features that an earlier error turned off.
     */
    override fun reset() {
        thinkingSupported = true
    }

    /** Installs App Check and fetches Remote Config. Safe to call when Firebase isn't configured. */
    fun initialize() {
        if (!isConfigured) return
        runCatching { AppCheckInstaller.install() }.onFailure { Log.w(TAG, "App Check setup failed: ${it.javaClass.simpleName}") }
        runCatching {
            val rc = FirebaseRemoteConfig.getInstance()
            rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 12 * 60 * 60 })
            rc.setDefaultsAsync(mapOf(ModelSelection.REMOTE_CONFIG_KEY to ModelSelection.DEFAULT_MODEL, AiTimeouts.REMOTE_CONFIG_KEY to AiTimeouts.DEFAULT_MILLIS))
            rc.fetchAndActivate()
        }.onFailure { Log.w(TAG, "Remote Config unavailable: ${it.javaClass.simpleName}") }
    }

    override suspend fun generate(systemInstruction: String, history: List<AiTurn>, message: String): AiResult {
        if (!isConfigured) return AiResult.Failure(AiFailure.NOT_CONFIGURED)
        val primary = modelName
        val first = attempt(primary, systemInstruction, history, message)
        return if (first is AiResult.Failure && first.reason == AiFailure.MODEL_UNAVAILABLE && primary != ModelSelection.FALLBACK_MODEL) {
            attempt(ModelSelection.FALLBACK_MODEL, systemInstruction, history, message)
        } else {
            first
        }
    }

    private suspend fun attempt(model: String, system: String, history: List<AiTurn>, message: String): AiResult {
        val result = call(model, system, history, message, useThinking = thinkingSupported)
        if (result is AiResult.Failure && thinkingSupported && result.reason == AiFailure.OTHER &&
            result.detail.orEmpty().contains("thinking", ignoreCase = true)
        ) {
            thinkingSupported = false
            return call(model, system, history, message, useThinking = false)
        }
        return result
    }

    private suspend fun call(model: String, system: String, history: List<AiTurn>, message: String, useThinking: Boolean): AiResult = try {
        val timeoutMillis = requestTimeoutMillis
        val generative = Firebase.ai(backend = GenerativeBackend.googleAI(), useLimitedUseAppCheckTokens = true).generativeModel(
            modelName = model,
            generationConfig = generationConfig {
                temperature = 0.5f
                maxOutputTokens = 1024
                if (useThinking) thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
            },
            systemInstruction = content { text(system) },
            requestOptions = RequestOptions(timeoutInMillis = timeoutMillis),
        )
        val contents: List<Content> = history.map { turn ->
            content(if (turn.role == AiTurn.Role.USER) "user" else "model") { text(turn.text) }
        } + content("user") { text(message) }
        val text: String? = withTimeout(timeoutMillis + 2_000) { generative.generateContent(contents).text }
        if (text.isNullOrBlank()) AiResult.Failure(AiFailure.EMPTY) else AiResult.Reply(text, model)
    } catch (e: TimeoutCancellationException) {
        AiResult.Failure(AiFailure.TIMEOUT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reason = AiErrorClassifier.classify(e.javaClass.simpleName, e.message, e.isNetworkFailure())
        val status = AiErrorClassifier.httpStatus(e.message)
        val retryAfter = AiErrorClassifier.retryAfterSeconds(e.message)
        // Log only the category, type and status code; never request content, tokens or keys.
        Log.w(TAG, "Gemini request failed: $reason (${e.javaClass.simpleName}, status=${status ?: "?"}, retryAfter=${retryAfter ?: "-"}s)")
        AiResult.Failure(reason, e.message?.take(300), retryAfterSeconds = retryAfter, httpStatus = status)
    }

    /** True if any cause in the chain is a network I/O failure (no connection, DNS, socket). */
    private fun Throwable.isNetworkFailure(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is IOException }

    private companion object { const val TAG = "PenelopeAI" }
}
