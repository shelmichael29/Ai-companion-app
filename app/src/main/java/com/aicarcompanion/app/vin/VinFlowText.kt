package com.aicarcompanion.app.vin

import com.aicarcompanion.app.data.vehicledb.VinValidator

/** Everything Penelope says or shows about finding and checking a VIN. Spoken text never contains the full VIN. */
object VinFlowText {
    const val GUIDE_TITLE = "Where to find your VIN"
    const val GUIDE_INTRO = "Your VIN (Vehicle Identification Number) is a 17-character code that's unique to your car. It lets me fill in your car's details for you."

    data class Place(val title: String, val detail: String)

    val PLACES = listOf(
        Place("On the dashboard", "Stand outside the car, on the driver's side, and look through the windshield at the bottom corner of the dashboard."),
        Place("On the driver's door", "Open the driver's door and look at the sticker on the door frame (the door jamb). The VIN is on that label."),
        Place("On your papers", "It's printed on your vehicle registration and your insurance card or policy."),
    )

    const val SCAN_HINT = "Point the camera at the VIN and hold steady. Use the flashlight if it's dark or shiny."
    const val SEARCHING = "Looking for a VIN…"
    const val READING = "Found something that looks like a VIN. Hold steady…"
    const val CONFLICT = "I'm seeing more than one possible VIN. Move closer to just one."
    const val CONFIRM_TITLE = "Is this your VIN?"
    const val CONFIRM_HINT = "Compare it with your car, one character at a time. You can fix any character."
    const val WORKING = "Looking up your car…"

    /** Spoken or announced when the guide opens. */
    val SPOKEN_GUIDE: String =
        "$GUIDE_INTRO You can find it in three places. " + PLACES.joinToString(" ") { "${it.title}: ${it.detail}" }

    fun lastFour(vin: String): String = VinValidator.normalize(vin).takeLast(4)

    /** Never the full VIN, even out loud. */
    fun foundSpoken(vin: String) = "I found a VIN ending in ${lastFour(vin).toList().joinToString(" ")}. Please check that it matches your car."

    const val UNCERTAIN_WARNING =
        "This doesn't pass the VIN check. Some characters may have been misread. Compare each one with your car before continuing. " +
            "(A few non-U.S. cars don't use the check, so it can still be right.)"

    fun correctedNote(count: Int) =
        "I changed $count character${if (count == 1) "" else "s"} that can't appear in a VIN (the letters I, O and Q look like 1 and 0)."

    const val PERMISSION_NEEDED =
        "To scan, Penelope needs to use the camera. The picture stays on your phone, is never saved or sent anywhere, and the camera is only on while this screen is open."
    const val PERMISSION_BLOCKED =
        "Camera access is turned off for Penelope. You can turn it on in your phone's settings, or just type your VIN."
    const val NO_CAMERA = "I couldn't start the camera. You can type your VIN instead."

    /** The VIN in groups, easier to compare by eye: "WBAFR1C5 6BC 000001". */
    fun grouped(vin: String): String {
        val v = VinValidator.normalize(vin)
        return listOf(v.take(8), v.drop(8).take(1), v.drop(9).take(1), v.drop(10).take(1), v.drop(11)).filter { it.isNotEmpty() }
            .let { parts -> if (parts.size == 5) "${parts[0]} ${parts[1]}${parts[2]}${parts[3]} ${parts[4]}" else parts.joinToString(" ") }
    }
}
