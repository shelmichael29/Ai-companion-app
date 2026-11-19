package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea

/**
 * Requests AUDIOFOCUS_GAIN_TRANSIENT with assistant attributes: music/podcasts pause while
 * Penelope is in a conversation and resume afterwards. Navigation prompts that only ask us to
 * duck are ignored.
 */
class AndroidAudioFocus(context: Context, private val log: ConnectionLog? = null) : AudioFocusController {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var count = 0
    override var onFocusLost: (() -> Unit)? = null

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            log?.record(LogArea.AUDIO, "Audio focus changed", mapOf("change" to focusChangeName(change)))
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onFocusLost?.invoke()
            }
        }
        .build()

    @Synchronized
    override fun acquire() {
        if (count++ == 0) {
            val result = audioManager.requestAudioFocus(request)
            // Denied usually means a phone call is active; recorded so "Penelope was silent" can be traced.
            log?.record(LogArea.AUDIO, "Audio focus requested", mapOf("result" to requestResultName(result)))
        }
    }

    @Synchronized
    override fun release() {
        if (count == 0) return
        if (--count == 0) {
            audioManager.abandonAudioFocusRequest(request)
            log?.record(LogArea.AUDIO, "Audio focus released")
        }
    }

    private companion object {
        fun requestResultName(result: Int) = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
            else -> "denied"
        }

        fun focusChangeName(change: Int) = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "gain"
            AudioManager.AUDIOFOCUS_LOSS -> "loss"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "duck_request_ignored"
            else -> "other"
        }
    }
}
