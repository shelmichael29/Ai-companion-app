package com.aicarcompanion.app.handsfree.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.aicarcompanion.app.handsfree.MediaAction
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.VolumeLevel

/** Dispatches media keys through AudioManager (supported, needs no permission). */
class AudioManagerKeySink(context: Context) : MediaKeySink {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun send(keyCode: Int): Boolean = try {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        true
    } catch (_: RuntimeException) {
        false
    }
}

/** Media-stream volume through AudioManager. Hidden when the volume is fixed. */
class AudioManagerVolume(context: Context) : VolumeGateway {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun level(): VolumeLevel? =
        if (audio.isVolumeFixed) null else VolumeLevel(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    override fun adjust(direction: VolumeDirection) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction == VolumeDirection.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }
}

/**
 * Active media sessions from MediaSessionManager. Android only allows this for apps with
 * notification access (the listener component is the proof), so without it this reports NoAccess.
 */
class PlatformMediaSessionSource(
    private val context: Context,
    private val access: NotificationAccessState,
) : MediaSessionSource {
    private val manager = context.applicationContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, PenelopeNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var onChange: (() -> Unit)? = null
    @Volatile private var registered = false
    private var watched: List<MediaController> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        track(controllers.orEmpty())
        onChange?.invoke()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { onChange?.invoke() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { onChange?.invoke() }
    }

    override fun watch(listener: () -> Unit) {
        onChange = listener
        register()
    }

    /** Safe to call again after access is granted. */
    @Suppress("TooGenericExceptionCaught")
    private fun register() {
        if (registered || !access.granted.value) return
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsListener)
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            registered = true
        } catch (_: SecurityException) {
            registered = false
        }
    }

    @Synchronized
    private fun track(controllers: List<MediaController>) {
        watched.forEach { it.unregisterCallback(controllerCallback) }
        watched = controllers
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
    }

    @Synchronized
    private fun hasWatched() = watched.isNotEmpty()

    override fun snapshot(): SessionSnapshot {
        if (!access.granted.value) {
            // Access was turned off: the system dropped our listener, so register again if it comes back.
            registered = false
            return SessionSnapshot.NoAccess
        }
        register()
        val controllers = try {
            manager.getActiveSessions(component)
        } catch (_: SecurityException) {
            return SessionSnapshot.NoAccess
        }
        if (controllers.isNotEmpty() && !hasWatched()) track(controllers)
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
            ?: return SessionSnapshot.None
        val meta = chosen.metadata
        return SessionSnapshot.Active(
            ActiveMediaSession(
                appName = appLabel(chosen.packageName),
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                isPlaying = chosen.playbackState?.state == PlaybackState.STATE_PLAYING,
                transport = { action ->
                    val controls = chosen.transportControls
                    when (action) {
                        MediaAction.PLAY -> controls.play()
                        MediaAction.PAUSE -> controls.pause()
                        MediaAction.NEXT -> controls.skipToNext()
                        MediaAction.PREVIOUS -> controls.skipToPrevious()
                    }
                },
            ),
        )
    }

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
