package com.aicarcompanion.app.vehiclecontrol

/*
 * Future vehicle controls (Drive, Phase 3): a capability-only description of what a car could
 * offer (climate, heated seats, door locks...). There is deliberately NO command method here and
 * no "Available" state. A standard Bluetooth OBD-II adapter reads data; it is not a control
 * channel, so nothing in Penelope may send a control command to the car. A real control feature
 * needs a manufacturer-supported integration and its own safety review (see docs/HANDS_FREE.md).
 *
 * This package has no Android imports and is not connected to diagnostics/obd.
 */

/** A car function a manufacturer integration might one day expose. */
enum class VehicleControlFeature(val label: String) {
    CLIMATE("Climate"),
    HEATED_SEATS("Heated seats"),
    DOOR_LOCKS("Door locks"),
    WINDOWS("Windows"),
    REMOTE_START("Remote start"),
    OTHER("Other car functions"),
}

/** Why a feature can't be used, in words a driver understands. */
enum class UnavailableReason(val explanation: String) {
    OBD_ADAPTER_READ_ONLY("Standard OBD-II adapters read data; they can't control the car."),
    NO_MANUFACTURER_INTEGRATION("This needs a connection supported by your car's maker, which I don't have yet."),
    NO_VEHICLE("Choose a vehicle first."),
}

sealed interface VehicleControlAvailability {
    data class Unavailable(val reason: UnavailableReason) : VehicleControlAvailability
}

/** Reports whether a control could be offered. It cannot perform anything. */
interface VehicleControlProvider {
    fun availability(feature: VehicleControlFeature, hasVehicle: Boolean): VehicleControlAvailability
}

/** The only provider today: nothing is controllable. */
object NoVehicleControlProvider : VehicleControlProvider {
    override fun availability(feature: VehicleControlFeature, hasVehicle: Boolean): VehicleControlAvailability =
        VehicleControlAvailability.Unavailable(
            if (hasVehicle) UnavailableReason.OBD_ADAPTER_READ_ONLY else UnavailableReason.NO_VEHICLE,
        )
}
