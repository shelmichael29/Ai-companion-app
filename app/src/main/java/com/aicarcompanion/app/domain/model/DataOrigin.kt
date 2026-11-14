package com.aicarcompanion.app.domain.model

/**
 * Where a piece of vehicle data came from.
 *
 * Every diagnostic reading, trouble code and maintenance record carries an origin so the UI
 * (and any future mechanic export or AI prompt) can always tell the user whether data is real.
 * Sample data must never be presented as if it came from the user's vehicle.
 */
enum class DataOrigin(val label: String, val isFromVehicle: Boolean) {
    /** Built-in demonstration data. Not from any real vehicle. */
    SAMPLE("Sample data", isFromVehicle = false),

    /** Typed in by the user (e.g. a maintenance record from a receipt). */
    USER_ENTERED("Entered by you", isFromVehicle = false),

    /** Read from the vehicle through a Bluetooth OBD-II adapter. */
    OBD_BLUETOOTH("Vehicle via Bluetooth OBD-II", isFromVehicle = true),

    /** Read from the vehicle through a Wi-Fi OBD-II adapter. */
    OBD_WIFI("Vehicle via Wi-Fi OBD-II", isFromVehicle = true),
}
