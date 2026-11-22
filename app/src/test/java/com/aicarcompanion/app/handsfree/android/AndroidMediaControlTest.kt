package com.aicarcompanion.app.handsfree.android

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.aicarcompanion.app.handsfree.ActionResult
import com.aicarcompanion.app.handsfree.MediaAction
import com.aicarcompanion.app.handsfree.MediaSource
import com.aicarcompanion.app.handsfree.VolumeDirection
import com.aicarcompanion.app.handsfree.VolumeLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidMediaControlTest {

    private class RecordingKeys(var ok: Boolean = true) : MediaKeySink {
        val sent = mutableListOf<Int>()
        override fun send(keyCode: Int): Boolean { sent += keyCode; return ok }
    }

    private class FakeSessions(var next: () -> SessionSnapshot) : MediaSessionSource {
        var listener: (() -> Unit)? = null
        override fun snapshot() = next()
        override fun watch(listener: () -> Unit) { this.listener = listener }
    }

    private class FakeVolume(var level: VolumeLevel? = VolumeLevel(5, 15)) : VolumeGateway {
        val adjusted = mutableListOf<VolumeDirection>()
        override fun level() = level
        override fun adjust(direction: VolumeDirection) { adjusted += direction }
    }

    private val keys = RecordingKeys()
    private val volume = FakeVolume()

    private fun control(sessions: FakeSessions) = AndroidMediaControl(keys, sessions, volume)

    @Test fun `without access each action sends its own media key and never play-pause`() = runTest {
        val c = control(FakeSessions { SessionSnapshot.NoAccess })
        assertEquals(MediaSource.NoAccessKeysOnly, c.source.value)
        assertNull(c.nowPlaying.value)
        MediaAction.entries.forEach { assertEquals(ActionResult.Requested, c.perform(it)) }
        assertEquals(
            listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            keys.sent,
        )
        assertFalse(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE in keys.sent)
    }

    @Test fun `pause sends only the pause key`() = runTest {
        control(FakeSessions { SessionSnapshot.NoAccess }).perform(MediaAction.PAUSE)
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE), keys.sent)
    }

    @Test fun `an active session is controlled directly and now playing is shown`() = runTest {
        val used = mutableListOf<MediaAction>()
        val session = ActiveMediaSession("Sample Music", "Song", "Artist", true) { used += it }
        val c = control(FakeSessions { SessionSnapshot.Active(session) })
        assertEquals(MediaSource.Session("Sample Music"), c.source.value)
        assertEquals("Song", c.nowPlaying.value?.title)
        assertEquals(ActionResult.Requested, c.perform(MediaAction.NEXT))
        assertEquals(listOf(MediaAction.NEXT), used)
        assertTrue("session path doesn't use keys", keys.sent.isEmpty())
    }

    @Test fun `access on but nothing playing is reported honestly`() {
        val c = control(FakeSessions { SessionSnapshot.None })
        assertEquals(MediaSource.NoActiveSession, c.source.value)
    }

    @Test fun `a security exception falls back to keys only`() = runTest {
        val c = control(FakeSessions { throw SecurityException("no access") })
        assertEquals(MediaSource.NoAccessKeysOnly, c.source.value)
        assertEquals(ActionResult.Requested, c.perform(MediaAction.PLAY))
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PLAY), keys.sent)

        val denied = ActiveMediaSession("App", null, null, false) { throw SecurityException("revoked") }
        val c2 = control(FakeSessions { SessionSnapshot.Active(denied) })
        assertEquals(ActionResult.Requested, c2.perform(MediaAction.PAUSE))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, keys.sent.last())
    }

    @Test fun `a failed key dispatch is reported, not claimed`() = runTest {
        val failing = RecordingKeys(ok = false)
        val c = AndroidMediaControl(failing, FakeSessions { SessionSnapshot.NoAccess }, volume)
        assertTrue(c.perform(MediaAction.PLAY) is ActionResult.Failed)
    }

    @Test fun `changes from the media session refresh the state`() {
        var snapshot: SessionSnapshot = SessionSnapshot.None
        val sessions = FakeSessions { snapshot }
        val c = control(sessions)
        snapshot = SessionSnapshot.Active(ActiveMediaSession("App", "T", null, true) {})
        sessions.listener!!.invoke()
        assertEquals(MediaSource.Session("App"), c.source.value)
    }

    @Test fun `volume is adjusted and shown, and hidden when the car fixes it`() = runTest {
        val c = control(FakeSessions { SessionSnapshot.NoAccess })
        assertEquals(VolumeLevel(5, 15), c.volume.value)
        assertEquals(ActionResult.Done, c.adjustVolume(VolumeDirection.UP))
        assertEquals(listOf(VolumeDirection.UP), volume.adjusted)
        volume.level = null
        c.refresh()
        assertNull(c.volume.value)
        assertEquals(ActionResult.Failed("Volume is set by your car."), c.adjustVolume(VolumeDirection.DOWN))
        assertEquals(1, volume.adjusted.size)
    }

    @Test fun `the real volume gateway raises and lowers the music stream`() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val gateway = AudioManagerVolume(context)
        val before = gateway.level()!!
        assertEquals(5, before.current)
        gateway.adjust(VolumeDirection.UP)
        assertEquals(6, gateway.level()!!.current)
        gateway.adjust(VolumeDirection.DOWN)
        gateway.adjust(VolumeDirection.DOWN)
        assertEquals(4, gateway.level()!!.current)
        assertTrue(AudioManagerKeySink(context).send(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test fun `play-pause toggle key is never referenced in the hands-free code`() {
        File("src/main/java/com/aicarcompanion/app/handsfree").walkTopDown().filter { it.extension == "kt" }.forEach {
            assertFalse("${it.name} uses PLAY_PAUSE", it.readText().contains("KEYCODE_MEDIA_PLAY_PAUSE"))
        }
    }
}
