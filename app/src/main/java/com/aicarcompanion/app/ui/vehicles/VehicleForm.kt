package com.aicarcompanion.app.ui.vehicles

import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.Vehicle

enum class VehicleField { YEAR, MAKE, MODEL, TRIM, DISPLACEMENT, CYLINDERS, VIN, ODOMETER, NICKNAME }

/** Raw, editable text for the vehicle profile form. */
data class VehicleForm(
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val displacementLiters: String = "",
    val cylinders: String = "",
    val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NATURALLY_ASPIRATED,
    val vin: String = "",
    val odometerMiles: String = "",
    val nickname: String = "",
) {
    companion object {
        fun from(vehicle: Vehicle) = VehicleForm(
            year = vehicle.year.toString(),
            make = vehicle.make,
            model = vehicle.model,
            trim = vehicle.trim.orEmpty(),
            displacementLiters = vehicle.engine.displacementLiters?.toString().orEmpty(),
            cylinders = vehicle.engine.cylinders?.toString().orEmpty(),
            fuelType = vehicle.engine.fuelType,
            aspiration = vehicle.engine.aspiration,
            vin = vehicle.vin.orEmpty(),
            odometerMiles = vehicle.odometerMiles?.toString().orEmpty(),
            nickname = vehicle.nickname.orEmpty(),
        )
    }
}

sealed interface VehicleFormResult {
    data class Valid(val vehicle: Vehicle) : VehicleFormResult
    data class Invalid(val errors: Map<VehicleField, String>) : VehicleFormResult
}

object VehicleFormValidator {
    private const val MIN_YEAR = 1900
    private val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{17}$") // VINs never contain I, O or Q

    fun validate(form: VehicleForm, id: String, currentYear: Int): VehicleFormResult {
        val errors = mutableMapOf<VehicleField, String>()

        val year = form.year.trim().toIntOrNull()
        if (year == null || year !in MIN_YEAR..currentYear + 2) {
            errors[VehicleField.YEAR] = "Enter a year between $MIN_YEAR and ${currentYear + 2}"
        }
        if (form.make.isBlank()) errors[VehicleField.MAKE] = "Make is required"
        if (form.model.isBlank()) errors[VehicleField.MODEL] = "Model is required"

        val isElectric = form.fuelType == FuelType.ELECTRIC
        val displacement = form.displacementLiters.trim().takeIf { it.isNotEmpty() && !isElectric }
            ?.let { text ->
                text.toDoubleOrNull()?.takeIf { it in 0.1..20.0 }
                    ?: null.also { errors[VehicleField.DISPLACEMENT] = "Enter liters, e.g. 2.0" }
            }
        val cylinders = form.cylinders.trim().takeIf { it.isNotEmpty() && !isElectric }
            ?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..16 }
                    ?: null.also { errors[VehicleField.CYLINDERS] = "Enter 1 to 16" }
            }

        val vin = form.vin.trim().uppercase().takeIf { it.isNotEmpty() }
        if (vin != null && !VIN_PATTERN.matches(vin)) {
            errors[VehicleField.VIN] = "VIN must be 17 letters/digits (no I, O or Q)"
        }

        val odometer = form.odometerMiles.trim().replace(",", "").takeIf { it.isNotEmpty() }
            ?.let { text ->
                text.toIntOrNull()?.takeIf { it in 0..2_000_000 }
                    ?: null.also { errors[VehicleField.ODOMETER] = "Enter whole miles" }
            }

        if (errors.isNotEmpty()) return VehicleFormResult.Invalid(errors)

        return VehicleFormResult.Valid(
            Vehicle(
                id = id,
                year = year!!,
                make = form.make.trim(),
                model = form.model.trim(),
                trim = form.trim.trim().ifEmpty { null },
                engine = EngineSpec(
                    displacementLiters = displacement,
                    cylinders = cylinders,
                    fuelType = form.fuelType,
                    aspiration = if (isElectric) Aspiration.NATURALLY_ASPIRATED else form.aspiration,
                ),
                vin = vin,
                odometerMiles = odometer,
                nickname = form.nickname.trim().ifEmpty { null },
            ),
        )
    }
}
