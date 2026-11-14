package com.aicarcompanion.app.history

import com.aicarcompanion.app.data.DefaultDiagnosticsRepository
import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.local.ObservationEntity
import com.aicarcompanion.app.data.local.VehicleEntity
import com.aicarcompanion.app.data.local.toDomain
import com.aicarcompanion.app.data.local.toEntity
import com.aicarcompanion.app.data.memory.InMemoryHistoryRepository
import com.aicarcompanion.app.diagnostics.dtc.GenericDtcCatalog
import com.aicarcompanion.app.domain.model.DataOrigin
import com.aicarcompanion.app.domain.model.DtcStatus
import com.aicarcompanion.app.domain.model.EngineLayout
import com.aicarcompanion.app.domain.model.EngineSpec
import com.aicarcompanion.app.domain.model.IdentificationSource
import com.aicarcompanion.app.domain.model.ObservationKind
import com.aicarcompanion.app.domain.model.ServiceKind
import com.aicarcompanion.app.domain.model.Vehicle
import com.aicarcompanion.app.domain.model.VehicleIdentification
import com.aicarcompanion.app.ui.history.ObservationForm
import com.aicarcompanion.app.ui.history.ServiceForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HistoryTest {
    private val today = LocalDate.of(2026, 11, 14)

    @Test fun `service form validates and converts`() {
        val r = ServiceForm(
            kind = ServiceKind.REPAIR, title = " Water pump replaced ", date = "2026-10-02",
            odometer = "98,450", performedBy = "Indy shop", cost = "$1,249.50", notes = "Electric pump, N52",
        ).validate("id", "bmw", today).getOrThrow()
        assertEquals("Water pump replaced", r.title)
        assertEquals(ServiceKind.REPAIR, r.kind)
        assertEquals(98_450, r.odometerMiles)
        assertEquals(124_950L, r.costCents)
        assertEquals(DataOrigin.USER_ENTERED, r.origin)
    }

    @Test fun `service form rejects bad input`() {
        val e = ServiceForm(title = "", date = "10/02/2026", odometer = "abc", cost = "lots")
            .validate("id", "bmw", today).exceptionOrNull()!!.message!!
        assertTrue(e.contains("Describe the work"))
        assertTrue(e.contains("YYYY-MM-DD"))
        assertTrue(e.contains("whole miles"))
        assertTrue(e.contains("Cost"))
        assertTrue(ServiceForm(title = "Oil", date = "2027-01-01").validate("id", "bmw", today).isFailure)
    }

    @Test fun `observation keeps hypotheses unverified`() {
        val o = ObservationForm(ObservationKind.HYPOTHESIS, "Possibly a vacuum leak (PCV valve)", "p0171")
            .validate("id", "bmw", Instant.EPOCH).getOrThrow()
        assertFalse(o.kind.isVerified)
        assertEquals("P0171", o.relatedCode)
        assertTrue(ObservationKind.VERIFIED_FINDING.isVerified)
        assertTrue(ObservationForm(ObservationKind.NOTE, "x", "P9999").validate("id", "bmw", Instant.EPOCH).isFailure)
    }

    @Test fun `unknown stored observation kind is never treated as verified`() {
        val o = ObservationEntity("id", "bmw", 0, "SOMETHING_NEW", "text", null, "USER_ENTERED").toDomain()
        assertEquals(ObservationKind.NOTE, o.kind)
    }

    @Test fun `vehicle round-trips through the database mapping`() {
        val v = Vehicle(
            "bmw", 2011, "BMW", "528i", "F10", EngineSpec(3.0, 6, EngineLayout.INLINE, engineCode = "N52B30", fuelGrade = "Premium"),
            transmission = "Automatic, 8-speed", drivetrain = "Rear-Wheel Drive", vin = "WBAFR1C56BC000001", odometerMiles = 98_000,
            identification = VehicleIdentification(IdentificationSource.CATALOG, "30070", true),
        )
        assertEquals(v, v.toEntity(0).toDomain())
        val unknown = v.toEntity(0).copy(engineLayout = "FUTURE").toDomain()
        assertEquals(EngineLayout.UNKNOWN, unknown.engine.layout)
    }

    @Test fun `history is kept separately per vehicle`() = runTest {
        val h = InMemoryHistoryRepository()
        h.recordTroubleCodes("bmw", listOf("P0171" to DtcStatus.STORED), DataOrigin.OBD_BLUETOOTH, Instant.EPOCH)
        h.recordTroubleCodes("civic", emptyList(), DataOrigin.OBD_BLUETOOTH, Instant.EPOCH)
        assertEquals(listOf("P0171"), h.latestTroubleCodeRead("bmw").first()!!.codes.map { it.code })
        assertTrue(h.latestTroubleCodeRead("civic").first()!!.codes.isEmpty())
        assertNull(h.latestTroubleCodeRead("truck").first())
    }

    @Test fun `a later read with no codes replaces earlier codes as latest`() = runTest {
        val h = InMemoryHistoryRepository()
        h.recordTroubleCodes("bmw", listOf("P0171" to DtcStatus.STORED), DataOrigin.OBD_BLUETOOTH, Instant.ofEpochSecond(1))
        h.recordTroubleCodes("bmw", emptyList(), DataOrigin.OBD_BLUETOOTH, Instant.ofEpochSecond(2))
        assertTrue(h.latestTroubleCodeRead("bmw").first()!!.codes.isEmpty())
        assertEquals(1, h.troubleCodeHistory("bmw").first().size)
    }

    @Test fun `dashboard diagnostics are real by default and sample only in demo mode`() = runTest {
        val h = InMemoryHistoryRepository()
        val settings = InMemorySettingsStore(AppSettings(demoDataEnabled = false))
        val repo = DefaultDiagnosticsRepository(h, settings, GenericDtcCatalog, MutableStateFlow(emptyList()))
        assertTrue(repo.troubleCodes("bmw").first().isEmpty())
        assertTrue(repo.sensorReadings("bmw").first().isEmpty())

        h.recordTroubleCodes("bmw", listOf("P1234" to DtcStatus.STORED), DataOrigin.OBD_BLUETOOTH, Instant.EPOCH)
        val real = repo.troubleCodes("bmw").first().single()
        assertEquals(DataOrigin.OBD_BLUETOOTH, real.origin)
        assertTrue(real.description.contains("definition not in the offline list"))

        settings.update { it.copy(demoDataEnabled = true) }
        assertTrue(repo.troubleCodes("bmw").first().all { it.origin == DataOrigin.SAMPLE })
        assertTrue(repo.sensorReadings("bmw").first().all { it.origin == DataOrigin.SAMPLE })
    }
}
