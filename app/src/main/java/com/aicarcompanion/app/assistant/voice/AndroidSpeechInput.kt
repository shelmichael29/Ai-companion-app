package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Uses the phone's system speech recognizer (on Samsung devices, Google or Samsung's service).
 * Many recognizers transparently use on-device models when available. If the default
 * recognizer fails for network reasons and an on-device recognizer exists (Android 12+), we
 * retry once on-device.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    @Volatile private var active: SpeechRecognizer? = null

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(): Flow<SpeechInputEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(SpeechInputEvent.Error(SpeechInputError.UNAVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }

        var recognizer: SpeechRecognizer? = null
        var triedOnDevice = false

        fun start(onDevice: Boolean) {
            recognizer?.destroy()
            val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = r
            active = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechInputEvent.Ready) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    bestResult(partialResults)?.let { trySend(SpeechInputEvent.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    val text = bestResult(results)
                    trySend(
                        if (text.isNullOrBlank()) SpeechInputEvent.Error(SpeechInputError.NO_MATCH)
                        else SpeechInputEvent.Final(text),
                    )
                    close()
                }

                override fun onError(error: Int) {
                    if (SpeechErrorMapper.isNetworkRelated(error) && !onDevice && !triedOnDevice && onDeviceAvailable()) {
                        triedOnDevice = true
                        start(onDevice = true)
                        return
                    }
                    trySend(SpeechInputEvent.Error(SpeechErrorMapper.map(error)))
                    close()
                }
            })
            r.startListening(recognizerIntent())
        }

        start(onDevice = false)
        awaitClose {
            recognizer?.cancel()
            recognizer?.destroy()
            active = null
        }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer must be used on the main thread.

    override fun finishListening() {
        active?.stopListening()
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun bestResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
}
