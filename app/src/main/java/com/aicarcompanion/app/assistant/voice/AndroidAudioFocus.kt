package com.aicarcompanion.app.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Requests AUDIOFOCUS_GAIN_TRANSIENT with assistant attributes: music/podcasts pause while
 * Penelope is in a conversation and resume afterwards. Navigation prompts that only ask us to
 * duck are ignored.
 */
class AndroidAudioFocus(context: Context) : AudioFocusController {
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
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                onFocusLost?.invoke()
            }
        }
        .build()

    @Synchronized
    override fun acquire() {
        if (count++ == 0) audioManager.requestAudioFocus(request)
    }

    @Synchronized
    override fun release() {
        if (count == 0) return
        if (--count == 0) audioManager.abandonAudioFocusRequest(request)
    }
}
