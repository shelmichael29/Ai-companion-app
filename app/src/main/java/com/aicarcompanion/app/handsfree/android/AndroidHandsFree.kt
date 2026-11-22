package com.aicarcompanion.app.handsfree.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.aicarcompanion.app.connectivity.ConnectionLog
import com.aicarcompanion.app.connectivity.LogArea
import com.aicarcompanion.app.handsfree.AudioRoute
import com.aicarcompanion.app.handsfree.CapabilityProvider
import com.aicarcompanion.app.handsfree.CapabilityStatus
import com.aicarcompanion.app.handsfree.CarAudioRoute
import com.aicarcompanion.app.handsfree.HandsFreeCapability
import com.aicarcompanion.app.handsfree.PlatformRequirement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Penelope can do hands-free on Android, per capability (see docs/HANDS_FREE.md):
 * - Media: always available (media keys); what's playing needs notification access.
 * - Reading and replying to messages: need notification access (no SMS permission, ever).
 * - Calls: a handoff to the dialer; answering needs call permissions Penelope doesn't request.
 * - Car controls: not supported; a standard OBD-II adapter can't control the car.
 */
class AndroidHandsFreeCapabilities(private val notificationAccess: () -> Boolean = { false }) : CapabilityProvider {
    override fun status(capability: HandsFreeCapability): CapabilityStatus = when (capability) {
        HandsFreeCapability.MEDIA_CONTROL -> CapabilityStatus.Available
        HandsFreeCapability.READ_MESSAGES, HandsFreeCapability.SEND_MESSAGES ->
            if (notificationAccess()) CapabilityStatus.Available
            else CapabilityStatus.NeedsSetup(listOf(PlatformRequirement.NOTIFICATION_ACCESS))
        HandsFreeCapability.PLACE_CALLS -> CapabilityStatus.Available
        HandsFreeCapability.ANSWER_CALLS, HandsFreeCapability.NAVIGATION -> CapabilityStatus.NotImplemented
        HandsFreeCapability.VEHICLE_CONTROLS -> CapabilityStatus.NotSupported
    }
}

/**
 * Watches the phone's audio output route through AudioManager device callbacks. It needs no
 * permission, and records only the route type (never device names or addresses), so connection
 * problems ("Penelope talks through the phone, not the car") can be diagnosed.
 */
class AndroidCarAudioRoute(context: Context, private val log: ConnectionLog? = null) : CarAudioRoute {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _route = MutableStateFlow(AudioRoute.UNKNOWN)
    override val route: StateFlow<AudioRoute> = _route.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        update()
    }

    private fun update() {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        val next = AudioRouteRules.routeFor(types)
        if (next != _route.value) {
            _route.value = next
            log?.record(LogArea.AUDIO, "Audio output changed", mapOf("route" to next.label))
        }
    }
}

/** Picks the route media would play on from the connected output types (testable without Android). */
object AudioRouteRules {
    fun routeFor(outputTypes: Set<Int>): AudioRoute = when {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in outputTypes -> AudioRoute.CAR_BLUETOOTH_MEDIA
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO in outputTypes -> AudioRoute.CAR_BLUETOOTH_HANDS_FREE
        outputTypes.any { it in WIRED } -> AudioRoute.WIRED_OR_USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in outputTypes -> AudioRoute.PHONE_SPEAKER
        else -> AudioRoute.UNKNOWN
    }

    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_AUX_LINE,
    )
}
