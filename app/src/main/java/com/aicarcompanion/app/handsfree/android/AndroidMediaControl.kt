package com.aicarcompanion.app.handsfree.android

import android.view.KeyEvent
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.MediaAction
import com.aicarcompanion.app.handsfree.MediaControl
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.NowPlaying
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.VolumeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sends a media key (down then up) to whichever app Android treats as the media target. */
fun interface MediaKeySink {
    /** Returns false if the key couldn't be dispatched. */
    fun send(keyCode: Int): Boolean
}

/** A media app's session that Penelope may read and control (needs notification access). */
class ActiveMediaSession(
    val appName: String?,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val transport: (MediaAction) -> Unit,
) {
    override fun toString() = "ActiveMediaSession(redacted)"
}

sealed interface SessionSnapshot {
    data object NoAccess : SessionSnapshot
    data object None : SessionSnapshot
    class Active(val session: ActiveMediaSession) : SessionSnapshot
}

/** Where active media sessions come from. The real one is Android's MediaSessionManager. */
interface MediaSessionSource {
    fun snapshot(): SessionSnapshot
    /** Calls [listener] when sessions, playback or what's playing change. */
    fun watch(listener: () -> Unit)
}

/** The media volume, or null level when the volume is fixed (set by the car or phone). */
interface VolumeGateway {
    fun level(): VolumeLevel?
    fun adjust(direction: VolumeDirection)
}

/**
 * Media control through supported Android APIs only.
 *
 * With notification access Penelope reads the active media session (what's playing) and uses its
 * transport controls. Without it, Penelope sends separate PLAY, PAUSE, NEXT and PREVIOUS media
 * keys (never the toggle PLAY_PAUSE, which could start music the user just paused). Either way the
 * result is [ActionResult.Requested]: Penelope asked the media app, and can't promise it complied.
 */
class AndroidMediaControl(
    private val keys: MediaKeySink,
    private val sessions: MediaSessionSource,
    private val volumeGateway: VolumeGateway,
) : MediaControl {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()
    private val _source = MutableStateFlow<MediaSource>(MediaSource.NoAccessKeysOnly)
    override val source: StateFlow<MediaSource> = _source.asStateFlow()
    private val _volume = MutableStateFlow<VolumeLevel?>(null)
    override val volume: StateFlow<VolumeLevel?> = _volume.asStateFlow()

    init {
        sessions.watch { refresh() }
        refresh()
    }

    private fun snapshot(): SessionSnapshot = try {
        sessions.snapshot()
    } catch (_: SecurityException) {
        SessionSnapshot.NoAccess
    }

    override fun refresh() {
        when (val s = snapshot()) {
            SessionSnapshot.NoAccess -> { _source.value = MediaSource.NoAccessKeysOnly; _nowPlaying.value = null }
            SessionSnapshot.None -> { _source.value = MediaSource.NoActiveSession; _nowPlaying.value = null }
            is SessionSnapshot.Active -> {
                _source.value = MediaSource.Session(s.session.appName)
                _nowPlaying.value = NowPlaying(s.session.appName, s.session.title, s.session.artist, s.session.isPlaying)
            }
        }
        _volume.value = volumeGateway.level()
    }

    override suspend fun perform(action: MediaAction): ActionResult {
        val snapshot = snapshot()
        val result = if (snapshot is SessionSnapshot.Active) {
            try {
                snapshot.session.transport(action)
                ActionResult.Requested
            } catch (_: SecurityException) {
                sendKey(action)
            }
        } else {
            sendKey(action)
        }
        refresh()
        return result
    }

    private fun sendKey(action: MediaAction): ActionResult =
        if (keys.send(keyFor(action))) ActionResult.Requested else ActionResult.Failed("I couldn't reach your media app.")

    override suspend fun adjustVolume(direction: VolumeDirection): ActionResult {
        if (volumeGateway.level() == null) return ActionResult.Failed("Volume is set by your car.")
        volumeGateway.adjust(direction)
        refresh()
        return ActionResult.Done
    }

    companion object {
        /** Separate keys: PLAY_PAUSE is never used. */
        fun keyFor(action: MediaAction): Int = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
    }
}
