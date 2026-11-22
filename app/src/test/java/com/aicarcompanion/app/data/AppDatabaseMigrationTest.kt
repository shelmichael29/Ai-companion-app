package com.aicarcompanion.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.aicarcompanion.app.data.local.AppDatabase
import com.aicarcompanion.app.data.local.DashboardDao
import com.aicarcompanion.app.data.local.DashboardGaugeEntity
import com.aicarcompanion.app.data.local.DashboardLayoutEntity
import com.aicarcompanion.app.data.local.MIGRATION_1_2
import com.aicarcompanion.app.data.local.RoomDashboardRepository
import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.UnitChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a real version-1 database from the committed v1 schema (app/schemas/.../1.json), opens it
 * with Room at version 2 through [MIGRATION_1_2], and checks Room's own schema validation passes
 * and old data survives. No destructive fallback exists, so a wrong migration fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppDatabaseMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"
    private var db: AppDatabase? = null

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun close() { db?.close(); context.deleteDatabase(name) }

    private fun schemaFile(version: Int): File {
        val dir = File("schemas/com.aicarcompanion.app.data.local.AppDatabase")
        return File(dir, "$version.json").also { assertTrue("missing ${it.path}", it.exists()) }
    }

    private fun createV1() {
        val json = JSONObject(schemaFile(1).readText()).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        raw.execSQL("PRAGMA foreign_keys=OFF")
        val entities = json.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) raw.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = json.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
        raw.execSQL(
            "INSERT INTO vehicles (id, year, make, model, trim, displacementLiters, cylinders, engineLayout, fuelType, aspiration, " +
                "engineCode, fuelGrade, transmission, drivetrain, vin, odometerMiles, nickname, identificationSource, catalogId, " +
                "userConfirmed, updatedAtMillis) VALUES ('bmw', 2011, 'BMW', '528i', NULL, 3.0, 6, 'INLINE', 'GASOLINE', 'NATURAL', " +
                "'N52B30', NULL, NULL, NULL, NULL, 98000, NULL, 'USER', NULL, 1, 1)",
        )
        raw.version = 1
        raw.close()
    }

    private fun openV2(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2).allowMainThreadQueries().build().also { db = it }

    @Test fun `v2 schema file is committed and has the dashboard tables`() {
        val tables = JSONObject(schemaFile(2).readText()).getJSONObject("database").getJSONArray("entities")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("tableName") } }
        assertTrue(tables.containsAll(listOf("dashboard_layouts", "dashboard_gauges", "dashboard_prefs", "vehicles")))
    }

    @Test fun `a v1 database migrates to v2, keeps its vehicle and accepts layouts`() = runBlocking {
        createV1()
        val database = openV2()
        val vehicles = database.vehicleDao().observeAll().first()
        assertEquals(listOf("bmw"), vehicles.map { it.id })

        val dao = database.dashboardDao()
        dao.saveLayout(
            DashboardLayoutEntity("l1", "bmw", "Track", 0, 1, 1),
            listOf(DashboardGaugeEntity("l1", 0, 0x0C, "ANALOG", "LARGE", null)),
        )
        assertEquals(listOf("Track"), dao.observeLayouts("bmw").first().map { it.name })
        assertEquals(1, dao.observeGauges("bmw").first().size)
    }

    @Test fun `deleting the vehicle removes its layouts and gauges`() = runBlocking {
        createV1()
        val database = openV2()
        val dao = database.dashboardDao()
        dao.saveLayout(DashboardLayoutEntity("l1", "bmw", "A", 0, 1, 1), listOf(DashboardGaugeEntity("l1", 0, 0x0C, "DIGITAL", "SMALL", null)))
        database.vehicleDao().delete("bmw")
        assertTrue(dao.observeLayouts("bmw").first().isEmpty())
        assertTrue(dao.observeGauges("bmw").first().isEmpty())
    }

    @Test fun `the database itself refuses a ninth gauge and a repeated sensor`() = runBlocking {
        createV1()
        val dao: DashboardDao = openV2().dashboardDao()
        val layout = DashboardLayoutEntity("l1", "bmw", "Full", 0, 1, 1)
        val nine = (0 until 9).map { DashboardGaugeEntity("l1", it, it + 1, "DIGITAL", "SMALL", null) }
        try {
            dao.saveLayout(layout, nine)
            fail("nine gauges must be refused")
        } catch (expected: IllegalArgumentException) {
            // refused in the DAO transaction
        }
        assertTrue(dao.observeLayouts("bmw").first().isEmpty()) // nothing half-saved
        try {
            dao.saveLayout(layout, listOf(DashboardGaugeEntity("l1", 0, 5, "DIGITAL", "SMALL", null), DashboardGaugeEntity("l1", 1, 5, "DIGITAL", "SMALL", null)))
            fail("a repeated sensor must be refused")
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // unique (layoutId, pid)
        }
    }

    @Test fun `repository round trip keeps order, style, size, unit and active layout`() = runBlocking {
        createV1()
        val repo = RoomDashboardRepository(openV2().dashboardDao())
        val layout = DashboardLayout(
            "l1", "bmw", "Economy",
            listOf(GaugeSpec(0x05, GaugeStyle.ANALOG, GaugeSize.LARGE, UnitChoice.FAHRENHEIT), GaugeSpec(0x0D, GaugeStyle.LINE_GRAPH, GaugeSize.MEDIUM, UnitChoice.MPH)),
        )
        repo.save(layout)
        repo.setActive("bmw", "l1")
        assertEquals(layout, repo.layouts("bmw").first().single())
        assertEquals("l1", repo.activeLayoutId("bmw").first())
        repo.save(layout.copy(gauges = layout.gauges.reversed()))
        assertEquals(listOf(0x0D, 0x05), repo.layouts("bmw").first().single().gauges.map { it.pid })
        repo.delete("l1")
        assertTrue(repo.layouts("bmw").first().isEmpty())
        assertNull(repo.activeLayoutId("bmw").first())
        assertFalse(repo.layouts("other").first().any())
    }
}
