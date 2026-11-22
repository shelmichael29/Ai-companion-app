package com.aicarcompanion.app.handsfree

import com.aicarcompanion.app.vehiclecontrol.VehicleControlFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive (Phase 3) additions to the voice command router: volume, dictated replies, car controls. */
class VoiceCommandRouterDriveTest {

    @Test fun `volume commands are recognized`() {
        mapOf(
            "turn the music up" to VolumeDirection.UP,
            "turn up the volume" to VolumeDirection.UP,
            "volume down" to VolumeDirection.DOWN,
            "music down" to VolumeDirection.DOWN,
            "turn down the music" to VolumeDirection.DOWN,
            "Penelope, turn the volume up" to VolumeDirection.UP,
        ).forEach { (said, dir) -> assertEquals(said, HandsFreeCommand.Volume(dir), VoiceCommandRouter.route(said)) }
    }

    @Test fun `a message body spoken with the command is captured`() {
        assertEquals(
            HandsFreeCommand.SendMessage("Sarah", "I'm running late"),
            VoiceCommandRouter.route("text Sarah saying I'm running late"),
        )
        assertEquals(HandsFreeCommand.SendMessage(null, "on my way"), VoiceCommandRouter.route("reply saying on my way"))
        assertEquals(HandsFreeCommand.SendMessage(null), VoiceCommandRouter.route("reply"))
        assertEquals(HandsFreeCommand.SendMessage("Mom", null), VoiceCommandRouter.route("send a message to Mom"))
    }

    @Test fun `car control requests are recognized as vehicle controls`() {
        mapOf(
            "turn on the heated seats" to VehicleControlFeature.HEATED_SEATS,
            "turn on the seat heaters" to VehicleControlFeature.HEATED_SEATS,
            "turn up the heat" to VehicleControlFeature.CLIMATE,
            "turn off the AC" to VehicleControlFeature.CLIMATE,
            "turn on the defrost" to VehicleControlFeature.CLIMATE,
            "lock the doors" to VehicleControlFeature.DOOR_LOCKS,
            "unlock the car" to VehicleControlFeature.DOOR_LOCKS,
            "start the car" to VehicleControlFeature.REMOTE_START,
        ).forEach { (said, feature) -> assertEquals(said, HandsFreeCommand.VehicleControl(feature), VoiceCommandRouter.route(said)) }
        assertEquals(HandsFreeCapability.VEHICLE_CONTROLS, HandsFreeCommand.VehicleControl(VehicleControlFeature.OTHER).capability)
    }

    @Test fun `questions and unrelated phrases are not captured`() {
        listOf(
            "how do I turn on the heated seats", "turn up your voice", "what does P0171 mean",
            "why won't the heater turn on", "can I lock the doors from the app", "louder", "quieter",
            "is the volume too loud for my speakers",
        ).forEach { said -> assertNull(said, VoiceCommandRouter.route(said)) }
    }

    @Test fun `commands holding names or text redact their toString`() {
        val text = listOf(
            HandsFreeCommand.SendMessage("Sarah", "secret body").toString(),
            HandsFreeCommand.PlaceCall("Mom").toString(),
        ).joinToString()
        assertFalse(text.contains("Sarah") || text.contains("secret") || text.contains("Mom"))
        assertTrue(HandsFreeCommand.Volume(VolumeDirection.UP).logName == "media_volume_up")
    }
}

class VoiceCommandRouterReviewFixesTest {
    @Test fun `send a message has no recipient`() {
        assertEquals(HandsFreeCommand.SendMessage(null), VoiceCommandRouter.route("send a message"))
        assertEquals(HandsFreeCommand.SendMessage(null), VoiceCommandRouter.route("send a text"))
    }

    @Test fun `tell someone a message is a dictated message but tell me is a question`() {
        assertEquals(HandsFreeCommand.SendMessage("Sarah", "I'm running late"), VoiceCommandRouter.route("tell Sarah I'm running late"))
        assertEquals(HandsFreeCommand.SendMessage("Sarah", "I'm running late"), VoiceCommandRouter.route("tell Sarah that I'm running late"))
        assertNull(VoiceCommandRouter.route("tell me what P0171 means"))
        assertNull(VoiceCommandRouter.route("tell us about the brakes"))
    }
}
