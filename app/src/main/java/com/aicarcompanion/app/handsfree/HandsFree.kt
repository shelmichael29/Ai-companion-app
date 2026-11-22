package com.aicarcompanion.app.handsfree

import kotlinx.coroutines.flow.StateFlow

/*
 * Hands-free foundation (Phase H0): platform-neutral contracts for media, messages, calls and
 * car audio. This package must not import android.* (a unit test enforces it), so an iOS app can
 * implement the same contracts natively. Android implementations live in handsfree/android.
 *
 * Each capability reports its real status on the platform (see docs/HANDS_FREE.md), and Penelope
 * says so honestly instead of guessing. Anything holding names, numbers or message text redacts
 * them from toString so they can't reach a log by accident.
 */

/** Something Penelope may be able to do hands-free. */
enum class HandsFreeCapability(val spokenName: String) {
    MEDIA_CONTROL("control your music"),
    READ_MESSAGES("read your messages"),
    SEND_MESSAGES("send messages"),
    PLACE_CALLS("make phone calls"),
    ANSWER_CALLS("answer phone calls"),
    NAVIGATION("give directions"),
    VEHICLE_CONTROLS("control your car's features"),
}

/**
 * What a capability needs from the user or platform before it can work. These differ between
 * Android and iOS, and some may never be possible on one of them. See docs/HANDS_FREE.md.
 */
enum class PlatformRequirement(val description: String) {
    NOTIFICATION_ACCESS("Turn on notification access for Penelope in system settings"),
    CALL_PERMISSION("Allow Penelope to make and manage phone calls"),
    CONTACTS_PERMISSION("Allow Penelope to look up your contacts"),
    DEFAULT_DIALER_ROLE("Make Penelope the default phone app (not planned)"),
    USER_CONFIRMATION_PER_ACTION("The system asks you to confirm each action"),
}

/** Whether a capability can be used right now, and if not, why. */
sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    /** Possible on this platform once the user grants or turns something on. */
    data class NeedsSetup(val requirements: List<PlatformRequirement>) : CapabilityStatus
    /** The platform doesn't allow it for an app like Penelope. */
    data object NotSupported : CapabilityStatus
    /** Not built yet. */
    data object NotImplemented : CapabilityStatus
}

/** Reports which hands-free capabilities this platform and build can offer. */
interface CapabilityProvider {
    fun status(capability: HandsFreeCapability): CapabilityStatus
}

/** The outcome of a hands-free action, in terms Penelope can explain. */
sealed interface ActionResult {
    data object Done : ActionResult
    data class Unavailable(val status: CapabilityStatus) : ActionResult
    /** The user must confirm first (sending a message, calling someone). */
    data object NeedsConfirmation : ActionResult
    /**
     * The request was handed to another app or the system (a media key, the dialer, a notification
     * reply). Penelope can't know the outcome, so it says "asked" or "sent", never "delivered".
     */
    data object Requested : ActionResult
    data class Failed(val reason: String) : ActionResult
}

// ---- Media -----------------------------------------------------------------------------------

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

/** What's playing. Shown and spoken only when the user asks; never logged. */
data class NowPlaying(val appName: String?, val title: String?, val artist: String?, val isPlaying: Boolean) {
    override fun toString() = "NowPlaying(redacted)"
}

/** How Penelope can reach the media app right now. Never implies universal control. */
sealed interface MediaSource {
    /** No notification access: only generic media keys can be sent; what's playing is unknown. */
    data object NoAccessKeysOnly : MediaSource
    /** Access is on but no media app has an active session. */
    data object NoActiveSession : MediaSource
    /** A media app has a session Penelope can read and control. */
    data class Session(val appName: String?) : MediaSource
}

data class VolumeLevel(val current: Int, val max: Int)

enum class VolumeDirection { UP, DOWN }

/** Controls whatever media app is currently playing (music, podcasts, audiobooks). */
interface MediaControl {
    val nowPlaying: StateFlow<NowPlaying?>
    val source: StateFlow<MediaSource>
    /** Media volume, or null when the volume is fixed by the car or phone. */
    val volume: StateFlow<VolumeLevel?>
    suspend fun perform(action: MediaAction): ActionResult
    suspend fun adjustVolume(direction: VolumeDirection): ActionResult
    /** Re-reads what's playing and the volume (the screen came back, or access changed). */
    fun refresh() {}
}

// ---- Messages --------------------------------------------------------------------------------

/** An incoming message. Its text is read aloud only on request and is never logged or sent to AI. */
data class IncomingMessage(
    val id: String,
    val senderDisplayName: String,
    val text: String,
    val appName: String?,
    /** True only while the message's notification still offers a reply action. */
    val canReply: Boolean = false,
) {
    override fun toString() = "IncomingMessage(redacted)"
}

interface MessagingService {
    /** How many messages are waiting (a count only, safe to show anywhere). */
    val unreadCount: StateFlow<Int>
    /** Unread messages, newest last. */
    suspend fun unread(): List<IncomingMessage>
    /**
     * Asks the messaging app to send a reply. Implementations must refuse unless [confirmed] is true, i.e. Penelope read the
     * message back and the user said yes ([HandsFreeSafety.requiresConfirmation]).
     */
    suspend fun reply(to: IncomingMessage, text: String, confirmed: Boolean): ActionResult
}

// ---- Calls -----------------------------------------------------------------------------------

sealed interface CallState {
    data object Idle : CallState
    data class Ringing(val callerDisplayName: String?) : CallState {
        override fun toString() = "Ringing(redacted)"
    }
    data object InCall : CallState
}

/** Who to call. The number and name stay in memory only. */
data class CallTarget(val displayName: String?, val number: String) {
    override fun toString() = "CallTarget(redacted)"
}

interface CallService {
    val state: StateFlow<CallState>
    /** Opens the phone's dialer for [target] (the user taps call). Must refuse unless [confirmed] is true. */
    suspend fun dial(target: CallTarget, confirmed: Boolean): ActionResult
    suspend fun answer(): ActionResult
    suspend fun decline(): ActionResult
    suspend fun hangUp(): ActionResult
}

// ---- Car audio connection --------------------------------------------------------------------

/** Where Penelope's voice currently plays. Used for diagnostics and, later, call/microphone routing. */
enum class AudioRoute(val label: String) {
    CAR_BLUETOOTH_MEDIA("Bluetooth audio (A2DP)"),
    CAR_BLUETOOTH_HANDS_FREE("Bluetooth hands-free (SCO)"),
    WIRED_OR_USB("Wired or USB audio"),
    PHONE_SPEAKER("Phone speaker"),
    UNKNOWN("Unknown"),
}

/** Watches the phone's audio output route (no device names or addresses). */
interface CarAudioRoute {
    val route: StateFlow<AudioRoute>
}
