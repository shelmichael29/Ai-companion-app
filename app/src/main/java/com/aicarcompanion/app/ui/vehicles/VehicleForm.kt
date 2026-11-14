package com.aicarcompanion.app.ui.vehicles

import com.aicarcompanion.app.data.vehicledb.CatalogVehicleSpecs
import com.aicarcompanion.app.data.vehicledb.DecodedVehicle
import com.aicarcompanion.app.data.vehicledb.VehicleConfiguration
import com.aicarcompanion.app.data.vehicledb.VinValidator
import com.aicarcompanion.app.domain.model.Aspiration
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.FuelType
import com.aicarcompanion.app.domain.model.IdentificationSource
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.model.VehicleIdentification
import java.util.Locale

enum class VehicleField { YEAR, MAKE, MODEL, TRIM, DISPLACEMENT, CYLINDERS, VIN, ODOMETER, NICKNAME }

/** Raw, editable text for the vehicle profile form. */
data class VehicleForm(
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val displacementLiters: String = "",
    val cylinders: String = "",
    val layout: EngineLayout = EngineLayout.UNKNOWN,
    val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NATURALLY_ASPIRATED,
    val engineCode: String = "",
    val fuelGrade: String = "",
    val transmission: String = "",
    val drivetrain: String = "",
    val vin: String = "",
    val odometerMiles: String = "",
    val nickname: String = "",
    val source: IdentificationSource = IdentificationSource.MANUAL,
    val catalogId: String? = null,
) {
    /** Fills in details chosen from the vehicle catalog. The engine layout is left for the user to confirm. */
    fun withCatalogSelection(
        year: Int,
        make: String,
        model: String,
        configuration: VehicleConfiguration,
        specs: CatalogVehicleSpecs?,
    ): VehicleForm {
        val displacement = specs?.displacementLiters ?: configuration.displacementLiters
        val cylinders = specs?.cylinders ?: configuration.cylinders
        return copy(
            year = year.toString(),
            make = make,
            model = model,
            displacementLiters = displacement?.let(::formatLiters).orEmpty(),
            cylinders = cylinders?.toString().orEmpty(),
            fuelType = specs?.fuelType ?: inferFuel(configuration) ?: fuelType,
            aspiration = specs?.aspiration ?: inferAspiration(configuration),
            fuelGrade = specs?.fuelGrade.orEmpty(),
            transmission = specs?.transmission?.let { com.aicarcompanion.app.data.vehicledb.TransmissionText.describe(it) }
                ?: configuration.transmissionLabel,
            drivetrain = specs?.drivetrain ?: drivetrain,
            source = IdentificationSource.CATALOG,
            catalogId = configuration.id,
        )
    }

    /** Fills in only the fields a VIN decode actually determined; everything else is kept. */
    fun withDecodedVin(decoded: DecodedVehicle): VehicleForm = copy(
        vin = decoded.vin,
        year = decoded.year?.toString() ?: year,
        make = decoded.make ?: make,
        model = decoded.model ?: model,
        trim = decoded.trim ?: trim,
        displacementLiters = decoded.displacementLiters?.let(::formatLiters) ?: displacementLiters,
        cylinders = decoded.cylinders?.toString() ?: cylinders,
        layout = if (decoded.layout != EngineLayout.UNKNOWN) decoded.layout else layout,
        engineCode = decoded.engineCode ?: engineCode,
        fuelType = decoded.fuelType ?: fuelType,
        aspiration = decoded.aspiration ?: aspiration,
        transmission = decoded.transmission ?: transmission,
        drivetrain = decoded.drivetrain ?: drivetrain,
        source = IdentificationSource.VIN_DECODE,
    )

    companion object {
        fun from(vehicle: Vehicle) = VehicleForm(
            year = vehicle.year.toString(),
            make = vehicle.make,
            model = vehicle.model,
            trim = vehicle.trim.orEmpty(),
            displacementLiters = vehicle.engine.displacementLiters?.let(::formatLiters).orEmpty(),
            cylinders = vehicle.engine.cylinders?.toString().orEmpty(),
            layout = vehicle.engine.layout,
            fuelType = vehicle.engine.fuelType,
            aspiration = vehicle.engine.aspiration,
            engineCode = vehicle.engine.engineCode.orEmpty(),
            fuelGrade = vehicle.engine.fuelGrade.orEmpty(),
            transmission = vehicle.transmission.orEmpty(),
            drivetrain = vehicle.drivetrain.orEmpty(),
            vin = vehicle.vin.orEmpty(),
            odometerMiles = vehicle.odometerMiles?.toString().orEmpty(),
            nickname = vehicle.nickname.orEmpty(),
            source = vehicle.identification.source,
            catalogId = vehicle.identification.catalogId,
        )

        private fun formatLiters(value: Double) = String.format(Locale.US, "%.1f", value)

        private fun inferFuel(c: VehicleConfiguration): FuelType? = when {
            c.extras.any { it.equals("Diesel", true) } -> FuelType.DIESEL
            c.extras.any { it.equals("FFV", true) } -> FuelType.FLEX_FUEL
            c.extras.any { it.contains("Hybrid", true) } -> FuelType.HYBRID
            c.cylinders == null && c.displacementLiters == null -> FuelType.ELECTRIC
            else -> null
        }

        private fun inferAspiration(c: VehicleConfiguration): Aspiration = when {
            c.extras.any { it.equals("Turbo", true) } -> Aspiration.TURBOCHARGED
            c.extras.any { it.equals("S-Charge", true) || it.equals("Supercharger", true) } -> Aspiration.SUPERCHARGED
            else -> Aspiration.NATURALLY_ASPIRATED
        }
    }
}

sealed interface VehicleFormResult {
    data class Valid(val vehicle: Vehicle) : VehicleFormResult
    data class Invalid(val errors: Map<VehicleField, String>) : VehicleFormResult
}

object VehicleFormValidator {
    private const val MIN_YEAR = 1900

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
                    ?: null.also { errors[VehicleField.DISPLACEMENT] = "Enter liters, e.g. 3.0" }
            }
        val cylinders = form.cylinders.trim().takeIf { it.isNotEmpty() && !isElectric }
            ?.let { text ->
                text.toIntOrNull()?.takeIf { it in 1..16 }
                    ?: null.also { errors[VehicleField.CYLINDERS] = "Enter 1 to 16" }
            }

        val vin = form.vin.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            when (val r = VinValidator.validate(raw)) {
                is VinValidator.Result.Valid -> r.vin
                is VinValidator.Result.Invalid -> null.also { errors[VehicleField.VIN] = r.reason }
            }
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
                    layout = if (isElectric) EngineLayout.UNKNOWN else form.layout,
                    fuelType = form.fuelType,
                    aspiration = if (isElectric) Aspiration.NATURALLY_ASPIRATED else form.aspiration,
                    engineCode = form.engineCode.trim().ifEmpty { null },
                    fuelGrade = form.fuelGrade.trim().ifEmpty { null },
                ),
                transmission = form.transmission.trim().ifEmpty { null },
                drivetrain = form.drivetrain.trim().ifEmpty { null },
                vin = vin,
                odometerMiles = odometer,
                nickname = form.nickname.trim().ifEmpty { null },
                identification = VehicleIdentification(
                    source = form.source,
                    catalogId = form.catalogId,
                    userConfirmed = true, // Saving the reviewed form is the user's confirmation.
                ),
            ),
        )
    }
}
