@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.aicarcompanion.app.connectivity

import com.aicarcompanion.app.assistant.VoiceSession
import com.aicarcompanion.app.assistant.VoiceState
import com.aicarcompanion.app.assistant.conversation.AssistantReply
import com.aicarcompanion.app.assistant.conversation.AssistantRequest
import com.aicarcompanion.app.assistant.conversation.ConversationManager
import com.aicarcompanion.app.assistant.conversation.ReasoningProvider
import com.aicarcompanion.app.assistant.conversation.ReplyBasis
import com.aicarcompanion.app.assistant.voice.NoAudioFocus
import com.aicarcompanion.app.assistant.voice.SpeakResult
import com.aicarcompanion.app.assistant.voice.SpeechInput
import com.aicarcompanion.app.assistant.voice.SpeechInputEvent
import com.aicarcompanion.app.assistant.voice.SpeechOutput
import com.aicarcompanion.app.assistant.voice.SpeechOutputState
import com.aicarcompanion.app.assistant.voice.VoiceConfig
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.FakeElmAdapter
import com.aicarcompanion.app.diagnostics.bluetooth.AdapterConnectException
import com.aicarcompanion.app.diagnostics.bluetooth.AdapterTransport
import com.aicarcompanion.app.diagnostics.bluetooth.BluetoothAvailability
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailure
import com.aicarcompanion.app.diagnostics.bluetooth.ConnectFailureClassifier
import com.aicarcompanion.app.diagnostics.obd.AdapterDevice
import com.aicarcompanion.app.diagnostics.obd.AdapterRadio
import com.aicarcompanion.app.diagnostics.obd.ElmLink
import com.aicarcompanion.app.diagnostics.obd.LinkHealth
import com.aicarcompanion.app.diagnostics.obd.ObdConnectionState
import com.aicarcompanion.app.diagnostics.obd.ObdManager
import com.aicarcompanion.app.diagnostics.obd.ObdPid
import com.aicarcompanion.app.diagnostics.obd.ObdTransport
import com.aicarcompanion.app.diagnostics.obd.ReadOnlyCommandPolicy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Bluetooth connection foundation: discovery, connection, timeouts, bounded retries, permissions,
 * automatic reconnection and plain-language status, all with simulated hardware. Nothing here
 * talks to a real adapter or car, and only read-only commands are ever sent.
 */
class BluetoothConnectionFoundationTest {

    /** A scripted Bluetooth transport. Each `open` takes the next scripted outcome. */
    private class FakeTransport : AdapterTransport {
        var availability = BluetoothAvailability.READY
        var discoveryAllowed = true
        var paired = listOf<AdapterDevice>()
        var nearby = listOf<AdapterDevice>()
        /** If true, discovery never finishes by itself (a misbehaving phone). */
        var discoveryHangs = false
        val outcomes = ArrayDeque<Any>() // ElmLink or Throwable
        val opened = mutableListOf<AdapterDevice>()
        val links = mutableListOf<FakeElmAdapter>()
        var discoveries = 0

        override fun availability() = availability
        override val requiredPermissions = listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN")
        override fun canDiscover() = discoveryAllowed
        override fun pairedDevices() = if (availability == BluetoothAvailability.READY) paired else emptyList()
        override fun discover(timeoutMillis: Long): Flow<AdapterDevice> = flow {
            discoveries++
            nearby.forEach { emit(it) }
            if (discoveryHangs) awaitCancellation()
        }
        override suspend fun open(device: AdapterDevice): ElmLink {
            opened += device
            return when (val next = outcomes.removeFirstOrNull() ?: FakeElmAdapter()) {
                is Throwable -> throw next
                is FakeElmAdapter -> next.also { links += it }
                else -> next as ElmLink
            }
        }
    }

    private val obdLink = AdapterDevice("OBDII", "00:1D:A5:00:00:01", AdapterRadio.CLASSIC)
    private val vlink = AdapterDevice("Vgate iCar Pro", "00:1D:A5:00:00:02", AdapterRadio.CLASSIC)
    private val headphones = AdapterDevice("Galaxy Buds", "AA:BB:CC:00:00:03", AdapterRadio.CLASSIC)
    private val bleDongle = AdapterDevice("OBDII BLE", "00:1D:A5:00:00:04", AdapterRadio.LE_ONLY)

    private val transport = FakeTransport()
    private var remembered: String? = null
    private val rememberCalls = mutableListOf<AdapterDevice>()
    private val log = ConnectionLog(now = { Instant.parse("2026-11-17T12:00:00Z") })

    private fun TestScope.obd() = ObdManager(
        backgroundScope, transport, InMemoryHistoryRepository(),
        now = { Instant.parse("2026-11-17T12:00:00Z") }, reconnectDelaysOverride = listOf(1_000), log = log,
        recheckDelaysOverride = listOf(1_000),
    )

    private fun TestScope.setup(obd: ObdManager) = AdapterConnectionManager(
        backgroundScope, transport, obd,
        lastAdapterAddress = { remembered },
        rememberAdapter = { rememberCalls += it; remembered = it.address },
        log = log, discoveryTimeoutMillis = 12_000, retryDelaysMillis = listOf(2_000, 5_000),
    )

    private fun TestScope.settle(ms: Long = 0) {
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    // ---- Discovery ------------------------------------------------------------------------------

    @Test fun `with nothing paired, a nearby OBD-II adapter is found and connected automatically`() = runTest {
        transport.nearby = listOf(headphones, obdLink)
        val obd = obd(); val m = setup(obd)
        val seen = mutableListOf<AdapterSetupState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.state.collect { seen += it } }
        m.connectAutomatically(); settle(); settle(3_000)
        assertTrue(seen.any { it is AdapterSetupState.Searching })
        assertTrue(seen.any { it is AdapterSetupState.Found })
        assertTrue(m.state.value is AdapterSetupState.Connected)
        assertEquals(listOf(obdLink), transport.opened) // never tries the headphones
        assertTrue(obd.state.value is ObdConnectionState.Connected)
    }

    @Test fun `a paired adapter is used without searching`() = runTest {
        transport.paired = listOf(headphones, obdLink)
        val m = setup(obd())
        m.connectAutomatically(); settle()
        assertEquals(0, transport.discoveries)
        assertEquals(listOf(obdLink), transport.opened)
        assertTrue(m.state.value is AdapterSetupState.Connected)
    }

    @Test fun `several nearby adapters means the user chooses, nothing is connected blindly`() = runTest {
        transport.nearby = listOf(obdLink, vlink)
        val m = setup(obd())
        m.connectAutomatically(); settle()
        val s = m.state.value as AdapterSetupState.NeedsChoice
        assertEquals(setOf(obdLink, vlink), s.candidates.toSet())
        assertTrue(transport.opened.isEmpty())
    }

    @Test fun `nothing nearby ends in a plain not-found message`() = runTest {
        transport.nearby = listOf(headphones, bleDongle)
        val m = setup(obd())
        m.connectAutomatically(); settle()
        assertEquals(AdapterSetupState.Failed(ConnectFailure.NOT_FOUND, null), m.state.value)
        assertTrue(transport.opened.isEmpty())
    }

    @Test fun `the manual search lists Classic devices only and never connects`() = runTest {
        transport.nearby = listOf(headphones, obdLink, bleDongle, obdLink)
        val m = setup(obd())
        var found: List<AdapterDevice>? = null
        m.search { found = it }; settle()
        assertEquals(listOf(headphones, obdLink), found)
        assertTrue(transport.opened.isEmpty())
        assertEquals(AdapterSetupState.Idle, m.state.value)
    }

    @Test fun `a search that never finishes by itself is still stopped (no battery drain)`() = runTest {
        transport.nearby = listOf(obdLink)
        transport.discoveryHangs = true
        val m = setup(obd())
        var found: List<AdapterDevice>? = null
        m.search { found = it }
        settle(12_000 + AdapterConnectionManager.DISCOVERY_GRACE_MILLIS + 1)
        assertEquals(listOf(obdLink), found)
        assertFalse(m.state.value is AdapterSetupState.Searching)
    }

    @Test fun `searching without scan permission asks for permission instead of saying not found`() = runTest {
        transport.discoveryAllowed = false
        val m = setup(obd())
        m.connectAutomatically(); settle()
        assertEquals(AdapterSetupState.Unavailable(ConnectFailure.PERMISSION_DENIED), m.state.value)
        assertEquals(0, transport.discoveries)
    }

    // ---- Connection --------------------------------------------------------------------------------

    @Test fun `a successful connection reaches the car and remembers the adapter`() = runTest {
        val obd = obd(); val m = setup(obd)
        m.connect(obdLink); settle()
        assertEquals(AdapterSetupState.Connected(obdLink), m.state.value)
        val c = obd.state.value as ObdConnectionState.Connected
        assertEquals(ObdTransport.BLUETOOTH_CLASSIC, c.transport)
        assertTrue("the car answered", c.vehicleProtocol != null)
        assertEquals(listOf(obdLink), rememberCalls)
    }

    @Test fun `a timeout is retried a bounded number of times, then stops`() = runTest {
        repeat(5) { transport.outcomes += AdapterConnectException(ConnectFailure.TIMEOUT, "read failed, socket might closed or timeout") }
        val m = setup(obd())
        m.connect(obdLink); settle()
        assertEquals(1, transport.opened.size)
        assertEquals("waiting before attempt 2", AdapterSetupState.Connecting(obdLink, 1, 3), m.state.value)
        settle(2_000); assertEquals(2, transport.opened.size)
        settle(5_000); assertEquals(3, transport.opened.size)
        settle(60_000)
        assertEquals("never more than 3 attempts", 3, transport.opened.size)
        val failed = m.state.value as AdapterSetupState.Failed
        assertEquals(ConnectFailure.TIMEOUT, failed.reason)
        assertTrue(rememberCalls.isEmpty())
    }

    @Test fun `a retry that succeeds connects normally`() = runTest {
        transport.outcomes += AdapterConnectException(ConnectFailure.ADAPTER_UNAVAILABLE, "socket closed")
        val m = setup(obd())
        m.connect(obdLink); settle(); settle(2_000)
        assertEquals(AdapterSetupState.Connected(obdLink), m.state.value)
        assertEquals(2, transport.opened.size)
    }

    @Test fun `an unavailable adapter fails with a plain explanation`() = runTest {
        repeat(3) { transport.outcomes += IOException("read failed, socket might closed or timeout, read ret: -1") }
        val obd = obd(); val m = setup(obd)
        m.connect(obdLink); settle(10_000)
        val failed = m.state.value as AdapterSetupState.Failed
        // "socket might closed or timeout" is a common Android message for an unreachable adapter.
        assertTrue(failed.reason == ConnectFailure.ADAPTER_UNAVAILABLE || failed.reason == ConnectFailure.TIMEOUT)
        assertTrue(failed.everydayMessage.startsWith("I couldn't connect. Let's try again."))
        val obdFailed = obd.state.value as ObdConnectionState.Failed
        assertFalse("raw Android text stays out of the message", obdFailed.message.contains("socket"))
        assertTrue("but is kept for DIY/Advanced", obdFailed.detail.orEmpty().contains("socket"))
    }

    @Test fun `Bluetooth off stops at once without any connection attempt`() = runTest {
        transport.availability = BluetoothAvailability.DISABLED
        val m = setup(obd())
        m.connectAutomatically(); settle(60_000)
        assertEquals(AdapterSetupState.Unavailable(ConnectFailure.BLUETOOTH_OFF), m.state.value)
        assertTrue(transport.opened.isEmpty())
        assertEquals(0, transport.discoveries)
    }

    @Test fun `permission denied stops at once without any connection attempt`() = runTest {
        transport.availability = BluetoothAvailability.NO_PERMISSION
        val m = setup(obd())
        m.connect(obdLink); settle(60_000)
        assertEquals(AdapterSetupState.Unavailable(ConnectFailure.PERMISSION_DENIED), m.state.value)
        assertTrue(transport.opened.isEmpty())
    }

    @Test fun `permission revoked during connecting is not retried`() = runTest {
        transport.outcomes += SecurityException("Need android.permission.BLUETOOTH_CONNECT permission")
        val m = setup(obd())
        m.connect(obdLink); settle(60_000)
        assertEquals(ConnectFailure.PERMISSION_DENIED, (m.state.value as AdapterSetupState.Failed).reason)
        assertEquals(1, transport.opened.size)
    }

    @Test fun `a Bluetooth Low Energy adapter is refused clearly`() = runTest {
        val m = setup(obd())
        m.connect(bleDongle); settle(60_000)
        assertEquals(ConnectFailure.UNSUPPORTED_ADAPTER, (m.state.value as AdapterSetupState.Failed).reason)
        assertTrue(transport.opened.isEmpty())
    }

    @Test fun `cancel stops retries and searching, and nothing runs afterwards`() = runTest {
        repeat(5) { transport.outcomes += AdapterConnectException(ConnectFailure.TIMEOUT) }
        val obd = obd(); val m = setup(obd)
        m.connect(obdLink); settle()
        m.cancel(); settle(60_000)
        assertEquals(1, transport.opened.size)
        assertEquals(AdapterSetupState.Idle, m.state.value)
        assertEquals(ObdConnectionState.Disconnected, obd.state.value)
    }

    // ---- Automatic reconnection ------------------------------------------------------------------

    @Test fun `on start, the adapter that last worked is reconnected quietly`() = runTest {
        remembered = vlink.address
        transport.paired = listOf(obdLink, vlink)
        val obd = obd(); val m = setup(obd)
        assertTrue(m.reconnectLastAdapter()); settle()
        assertEquals(listOf(vlink), transport.opened)
        assertEquals(0, transport.discoveries)
        assertTrue(obd.state.value is ObdConnectionState.Connected)
    }

    @Test fun `automatic reconnection does nothing without a remembered, paired adapter or with Bluetooth off`() = runTest {
        val m = setup(obd())
        assertFalse("nothing remembered", m.reconnectLastAdapter())
        remembered = obdLink.address
        assertFalse("not paired any more", m.reconnectLastAdapter())
        transport.paired = listOf(obdLink)
        transport.availability = BluetoothAvailability.DISABLED
        assertFalse("Bluetooth off", m.reconnectLastAdapter())
        settle(60_000)
        assertTrue(transport.opened.isEmpty())
    }

    @Test fun `automatic reconnection doesn't interrupt a working connection`() = runTest {
        remembered = obdLink.address
        transport.paired = listOf(obdLink)
        val m = setup(obd())
        m.connect(obdLink); settle()
        assertFalse(m.reconnectLastAdapter())
        assertEquals(1, transport.opened.size)
    }

    @Test fun `an adapter is remembered only after it really connected`() = runTest {
        repeat(3) { transport.outcomes += AdapterConnectException(ConnectFailure.TIMEOUT) }
        val m = setup(obd())
        m.connect(vlink); settle(60_000)
        assertNull(remembered)
        m.connect(obdLink); settle()
        assertEquals(obdLink.address, remembered)
    }

    @Test fun `an unexpected disconnection is detected and the link restored by itself`() = runTest {
        val first = FakeElmAdapter(); val second = FakeElmAdapter()
        transport.outcomes += first; transport.outcomes += second
        val obd = obd(); val m = setup(obd)
        val announced = mutableListOf<String>()
        ConnectionAnnouncer(backgroundScope) { announced += it }.watch(obd.state)
        m.connect(obdLink); settle()
        first.closed = true // adapter unplugged
        obd.readLiveValue(ObdPid.ENGINE_RPM); settle()
        assertTrue(obd.state.value is ObdConnectionState.Reconnecting)
        settle(1_000)
        assertTrue(obd.state.value is ObdConnectionState.Connected)
        assertEquals(
            listOf(ConnectionAnnouncements.CONNECTED, ConnectionAnnouncements.ADAPTER_LOST, ConnectionAnnouncements.RESTORED),
            announced,
        )
    }

    @Test fun `connection setup and recovery only ever send read-only commands`() = runTest {
        val first = FakeElmAdapter(); val second = FakeElmAdapter()
        transport.outcomes += first; transport.outcomes += second
        val obd = obd(); val m = setup(obd)
        m.connect(obdLink); settle()
        first.closed = true
        obd.readLiveValue(ObdPid.ENGINE_RPM); settle(5_000)
        (first.written + second.written).forEach { assertTrue("$it must be read-only", ReadOnlyCommandPolicy.isAllowed(it)) }
        assertFalse((first.written + second.written).any { it == "04" })
    }

    // ---- Everyday wording ------------------------------------------------------------------------

    @Test fun `Everyday messages are exactly the plain steps`() {
        assertEquals("Looking for your car adapter.", AdapterSetupState.Searching.everydayMessage)
        assertEquals("Found your adapter.", AdapterSetupState.Found(obdLink).everydayMessage)
        assertEquals("Connecting to your vehicle.", AdapterSetupState.Connecting(obdLink, 1, 3).everydayMessage)
        assertEquals("You're connected.", AdapterSetupState.Connected(obdLink).everydayMessage)
        assertTrue(AdapterSetupState.Failed(ConnectFailure.TIMEOUT, "x").everydayMessage.startsWith("I couldn't connect. Let's try again."))
    }

    @Test fun `Everyday messages never contain addresses, protocols or error codes`() {
        val technical = Regex("""([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|RFCOMM|\bSPP\b|ATSP|ISO 15765|\bCAN\b|errno|-1|[Ss]ocket|UUID|ELM327|Exception""")
        val states = buildList {
            add(AdapterSetupState.Idle); add(AdapterSetupState.Searching); add(AdapterSetupState.Found(obdLink))
            add(AdapterSetupState.NeedsChoice(listOf(obdLink, vlink))); add(AdapterSetupState.Connecting(obdLink, 2, 3))
            add(AdapterSetupState.Connected(obdLink))
            ConnectFailure.entries.forEach {
                add(AdapterSetupState.Failed(it, "read failed, socket might closed or timeout, read ret: -1 00:1D:A5:00:00:01"))
                add(AdapterSetupState.Unavailable(it))
            }
        }
        states.forEach { assertFalse("${it.everydayMessage} is too technical", technical.containsMatchIn(it.everydayMessage)) }
        listOf(
            ConnectionAnnouncements.CONNECTED, ConnectionAnnouncements.CAR_SILENT, ConnectionAnnouncements.CAR_STOPPED,
            ConnectionAnnouncements.ADAPTER_LOST, ConnectionAnnouncements.RESTORED, ConnectionAnnouncements.FAILED,
        ).forEach { assertFalse(it, technical.containsMatchIn(it)) }
    }

    @Test fun `the status card hides technical details in Everyday and shows them in DIY and Advanced`() {
        val failed = AdapterSetupState.Failed(ConnectFailure.ADAPTER_UNAVAILABLE, "read failed, socket might closed, read ret: -1")
        val (_, everyday) = com.aicarcompanion.app.ui.adapter.statusText(ObdConnectionState.Disconnected, failed, everyday = true)
        val (_, diy) = com.aicarcompanion.app.ui.adapter.statusText(ObdConnectionState.Disconnected, failed, everyday = false)
        assertEquals(failed.everydayMessage, everyday)
        assertTrue(diy.contains("socket"))
        val (title, _) = com.aicarcompanion.app.ui.adapter.statusText(
            ObdConnectionState.Disconnected, AdapterSetupState.Connecting(obdLink, 1, 3), everyday = true,
        )
        assertEquals("Connecting to your vehicle.", title)
    }

    @Test fun `Android error messages are classified into plain reasons`() {
        assertEquals(ConnectFailure.PERMISSION_DENIED, ConnectFailureClassifier.classify(SecurityException("x")))
        assertEquals(ConnectFailure.TIMEOUT, ConnectFailureClassifier.classify(IOException("Connection timed out")))
        assertEquals(ConnectFailure.BLUETOOTH_OFF, ConnectFailureClassifier.classify(IOException("Bluetooth is off")))
        assertEquals(ConnectFailure.ADAPTER_UNAVAILABLE, ConnectFailureClassifier.classify(IOException("Host is down")))
        assertEquals(ConnectFailure.UNKNOWN, ConnectFailureClassifier.classify(IllegalStateException("?")))
        assertEquals(ConnectFailure.TIMEOUT, ConnectFailureClassifier.classify(AdapterConnectException(ConnectFailure.TIMEOUT)))
        assertFalse(ConnectFailure.BLUETOOTH_OFF.retryable)
        assertFalse(ConnectFailure.PERMISSION_DENIED.retryable)
        assertTrue(ConnectFailure.TIMEOUT.retryable)
    }

    // ---- Voice announcements ---------------------------------------------------------------------

    @Test fun `announcement rules cover connect, silent car, loss, restore and failure`() {
        val ready = ObdConnectionState.Connected(ObdTransport.BLUETOOTH_CLASSIC, "OBDII", vehicleProtocol = "ISO 15765-4 CAN", adapterVersion = "ELM327 v1.5")
        val silent = ready.copy(vehicleProtocol = null)
        val shaky = ready.copy(health = LinkHealth.DEGRADED)
        val connecting = ObdConnectionState.Connecting(ObdTransport.BLUETOOTH_CLASSIC, "OBDII")
        val failed = ObdConnectionState.Failed("x", ConnectFailure.TIMEOUT)
        val lost = ObdConnectionState.Reconnecting("OBDII", 1, 3)
        assertEquals(ConnectionAnnouncements.CONNECTED, ConnectionAnnouncements.between(connecting, ready))
        assertEquals(ConnectionAnnouncements.CAR_SILENT, ConnectionAnnouncements.between(connecting, silent))
        assertEquals(ConnectionAnnouncements.CAR_STOPPED, ConnectionAnnouncements.between(ready, shaky))
        assertEquals(ConnectionAnnouncements.RESTORED, ConnectionAnnouncements.between(shaky, ready))
        assertEquals(ConnectionAnnouncements.ADAPTER_LOST, ConnectionAnnouncements.between(ready, lost))
        assertEquals(ConnectionAnnouncements.RESTORED, ConnectionAnnouncements.between(lost, ready))
        assertEquals(ConnectionAnnouncements.FAILED, ConnectionAnnouncements.between(connecting, failed))
        assertNull("the user's own disconnect isn't announced", ConnectionAnnouncements.between(ready, ObdConnectionState.Disconnected))
        assertNull("intermediate steps aren't announced", ConnectionAnnouncements.between(ObdConnectionState.Disconnected, connecting))
    }

    @Test fun `retries don't repeat the same announcement`() = runTest {
        val states = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
        val said = mutableListOf<String>()
        ConnectionAnnouncer(backgroundScope) { said += it }.watch(states)
        runCurrent()
        repeat(3) {
            states.value = ObdConnectionState.Connecting(ObdTransport.BLUETOOTH_CLASSIC, "OBDII"); runCurrent()
            states.value = ObdConnectionState.Failed("x", ConnectFailure.TIMEOUT); runCurrent()
        }
        assertEquals(listOf(ConnectionAnnouncements.FAILED), said)
    }

    private class Input : SpeechInput {
        private var channel = Channel<SpeechInputEvent>(Channel.UNLIMITED)
        override val isAvailable = true
        override fun listen(): Flow<SpeechInputEvent> = flow { channel = Channel(Channel.UNLIMITED); emitAll(channel) }
        override fun finishListening() = Unit
        fun say(text: String) { channel.trySend(SpeechInputEvent.Final(text)); channel.close() }
    }

    private class Output : SpeechOutput {
        override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Ready)
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult.Completed }
        override fun stop() = Unit
        override fun configure(config: VoiceConfig) = Unit
    }

    private object Echo : ReasoningProvider {
        override suspend fun respond(request: AssistantRequest) = AssistantReply("Answer to ${request.utterance}", ReplyBasis.CONVERSATION)
    }

    @Test fun `a connection notice during a conversation waits for the end of Penelope's reply`() = runTest {
        val input = Input(); val output = Output()
        val conversation = ConversationManager(Echo, { "Penelope" })
        val s = VoiceSession(this, input, output, conversation, NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        assertTrue(s.state.value is VoiceState.Listening)
        s.queueStatusNotice(ConnectionAnnouncements.ADAPTER_LOST)
        assertTrue("never spoken over the user", output.spoken.isEmpty())
        assertTrue("still listening", s.state.value is VoiceState.Listening)
        input.say("how is my coolant"); advanceUntilIdle()
        assertEquals(listOf("Answer to how is my coolant", ConnectionAnnouncements.ADAPTER_LOST), output.spoken)
        assertTrue("listening again afterwards", s.state.value is VoiceState.Listening)
        s.end(); advanceUntilIdle()
    }

    @Test fun `outside a conversation a connection notice is shown, not spoken`() = runTest {
        val output = Output()
        val conversation = ConversationManager(Echo, { "Penelope" })
        val s = VoiceSession(this, Input(), output, conversation, NoAudioFocus(), { true })
        s.queueStatusNotice(ConnectionAnnouncements.CONNECTED)
        advanceUntilIdle()
        assertTrue(output.spoken.isEmpty())
        assertEquals(ConnectionAnnouncements.CONNECTED, conversation.turns.value.last().text)
    }

    @Test fun `a notice waiting when the conversation ends is shown, not lost`() = runTest {
        val output = Output()
        val conversation = ConversationManager(Echo, { "Penelope" })
        val s = VoiceSession(this, Input(), output, conversation, NoAudioFocus(), { true })
        s.start(); advanceUntilIdle()
        s.queueStatusNotice(ConnectionAnnouncements.RESTORED)
        s.end(); advanceUntilIdle()
        assertTrue(output.spoken.none { it == ConnectionAnnouncements.RESTORED })
        assertEquals(ConnectionAnnouncements.RESTORED, conversation.turns.value.last().text)
    }
}
