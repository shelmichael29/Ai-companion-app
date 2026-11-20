package com.aicarcompanion.app.data.knowledge

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeCause
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeDtc
import com.aicarcompanion.app.diagnostics.knowledge.KnowledgeInfo
import com.aicarcompanion.app.diagnostics.knowledge.ManufacturerDtc
import com.aicarcompanion.app.diagnostics.knowledge.OfflineKnowledge
import com.aicarcompanion.app.diagnostics.knowledge.RecallCampaign
import com.aicarcompanion.app.diagnostics.knowledge.SafetyRating
import com.aicarcompanion.app.diagnostics.knowledge.TriageGuide
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The offline knowledge pack, shipped as `assets/knowledge/penelope_knowledge.db.gz` (built by
 * `tools/offline-knowledge/scripts/build_app_pack.py`). [install] unpacks it once into app-private
 * no-backup storage, verifies its SHA-256 against the manifest and opens it read-only. Until then,
 * or if anything fails, [available] is false and Penelope uses her built-in data.
 *
 * Nothing here is ever written, uploaded or logged; queries take only codes and year/make/model.
 */
class SqliteOfflineKnowledge(private val context: Context) : OfflineKnowledge {

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile override var info: KnowledgeInfo? = null
        private set
    override val available: Boolean get() = db != null

    /** Installs (first launch or new pack) and opens the database. Call off the main thread. */
    @Synchronized
    fun install(): Boolean {
        if (db != null) return true
        return runCatching {
            val manifest = JSONObject(context.assets.open(MANIFEST).bufferedReader().use { it.readText() })
            val sha = manifest.getString("database_sha256")
            val dir = File(context.noBackupFilesDir, "knowledge").apply { mkdirs() }
            val target = File(dir, "penelope_knowledge_${sha.take(12)}.db")
            if (!target.exists()) {
                val temp = File(dir, "installing.tmp")
                try {
                val digest = MessageDigest.getInstance("SHA-256")
                packStream().use { input ->
                    temp.outputStream().buffered().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == sha) { "Knowledge pack checksum mismatch" }
                check(temp.renameTo(target)) { "Couldn't install knowledge pack" }
                } finally {
                    temp.delete() // never leave a partial file behind (no space, bad checksum)
                }
                // Older packs are removed only once the new one is verified and in place.
                dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            }
            db = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            info = KnowledgeInfo(
                builtUtc = manifest.optString("built_utc"),
                recallSnapshot = manifest.optJSONArray("sources")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == "nhtsa_recall_post2010" }?.optString("downloaded_utc")?.take(10)
                },
            )
            true
        }.getOrElse {
            Log.w(TAG, "Offline knowledge unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * The build tools unpack `*.gz` assets when packaging (the APK's own zip compression still
     * applies), so the plain database is normally present; the gzip form is the fallback.
     */
    private fun packStream(): java.io.InputStream =
        runCatching { context.assets.open(PACK_PLAIN).buffered() }.getOrNull()
            ?: GZIPInputStream(context.assets.open(PACK_GZ).buffered())

    private inline fun <T> query(sql: String, args: Array<String>, read: (Cursor) -> T): T? {
        val d = db ?: return null
        return runCatching { d.rawQuery(sql, args).use(read) }.getOrNull()
    }

    private fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.int(col: String): Int? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)
    private fun String?.lines(): List<String> = this?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    override fun dtc(code: String): KnowledgeDtc? = query("SELECT * FROM dtc_generic WHERE code = ?", arrayOf(code.uppercase())) { c ->
        if (!c.moveToFirst()) null else KnowledgeDtc(
            code = c.str("code")!!,
            title = c.str("title").orEmpty(),
            description = c.str("description").orEmpty(),
            causes = c.str("causes").lines().map { line ->
                val (likelihood, label) = line.split('|', limit = 2).let { if (it.size == 2) it[0] to it[1] else null to it[0] }
                KnowledgeCause(likelihood?.ifBlank { null }, label)
            },
            symptoms = c.str("symptoms").lines(),
            turnsOnCheckEngineLight = c.int("mil") == 1,
            limpModePossible = c.int("limp_mode") == 1,
            diyPossible = c.int("diy")?.let { it == 1 },
            reviewed = c.str("reviewed"),
            category = c.str("category"),
        )
    }

    override fun manufacturerDtc(code: String, make: String): ManufacturerDtc? =
        query("SELECT code, make, description FROM dtc_manufacturer WHERE code = ? AND make = ?", arrayOf(code.uppercase(), make.uppercase())) { c ->
            if (!c.moveToFirst()) null else ManufacturerDtc(c.str("code")!!, c.str("make")!!, c.str("description")!!)
        }

    @Volatile private var guides: List<TriageGuide>? = null

    override fun triageGuides(): List<TriageGuide> = guides ?: (query("SELECT * FROM symptom_guide", emptyArray()) { c ->
        buildList {
            while (c.moveToNext()) add(
                TriageGuide(c.str("id")!!, c.str("symptom")!!, c.str("questions").lines(), c.str("systems").lines(), c.str("evidence").lines(), c.str("warning")),
            )
        }
    } ?: emptyList()).also { if (it.isNotEmpty()) guides = it }

    /**
     * The model name to use in NHTSA tables: the exact name when it exists for that year and make,
     * otherwise the only longer name that starts with it ("F-150" → "F-150 SUPERCREW"). Never a
     * guess between several ("MUSTANG" must not pick up "MUSTANG MACH E").
     */
    private fun nhtsaModel(table: String, year: Int, make: String, model: String): String? {
        val m = model.trim().uppercase()
        val exact = query("SELECT 1 FROM $table WHERE make = ? AND year = ? AND model = ? LIMIT 1", arrayOf(make.uppercase(), year.toString(), m)) { it.moveToFirst() }
        if (exact == true) return m
        val escaped = m.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val longer = query(
            "SELECT DISTINCT model FROM $table WHERE make = ? AND year = ? AND model LIKE ? ESCAPE '\\' LIMIT 2",
            arrayOf(make.uppercase(), year.toString(), "$escaped %"),
        ) { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
        return longer.singleOrNull()
    }

    override fun recalls(year: Int, make: String, model: String, limit: Int): List<RecallCampaign> {
        val m = nhtsaModel("recall_vehicle", year, make, model) ?: return emptyList()
        return query(
        "SELECT c.* FROM recall_campaign c WHERE c.campaign IN (SELECT campaign FROM recall_vehicle WHERE make = ? AND year = ? AND model = ?) " +
            "ORDER BY c.report_date DESC LIMIT ${limit.coerceIn(1, 50)}",
        arrayOf(make.uppercase(), year.toString(), m),
    ) { c ->
        buildList {
            while (c.moveToNext()) add(
                RecallCampaign(c.str("campaign")!!, c.str("component"), c.str("report_date"), c.str("summary"), c.str("consequence"), c.str("remedy")),
            )
        }
    }.orEmpty()
    }

    override fun recallCount(year: Int, make: String, model: String): Int {
        val m = nhtsaModel("recall_vehicle", year, make, model) ?: return 0
        return query(
            "SELECT COUNT(DISTINCT campaign) FROM recall_vehicle WHERE make = ? AND year = ? AND model = ?",
            arrayOf(make.uppercase(), year.toString(), m),
        ) { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
    }

    override fun safetyRatings(year: Int, make: String, model: String): List<SafetyRating> {
        val m = nhtsaModel("safety_rating", year, make, model) ?: return emptyList()
        return query(
        "SELECT * FROM safety_rating WHERE make = ? AND year = ? AND model = ? ORDER BY overall IS NULL, overall DESC",
        arrayOf(make.uppercase(), year.toString(), m),
    ) { c ->
        buildList {
            while (c.moveToNext()) add(SafetyRating(c.str("model")!!, c.str("body"), c.str("drive"), c.int("overall"), c.int("frontal"), c.int("side"), c.int("rollover")))
        }
    }.orEmpty()
    }

    // ---- EPA vehicles (used by OfflineEpaCatalog) ----------------------------------------------------

    fun epaYears(): List<Int> = query("SELECT DISTINCT year FROM epa_vehicle ORDER BY year DESC", emptyArray()) { c ->
        buildList { while (c.moveToNext()) add(c.getInt(0)) }
    }.orEmpty()

    fun epaMakes(year: Int): List<String> = query("SELECT DISTINCT make FROM epa_vehicle WHERE year = ? ORDER BY make", arrayOf(year.toString())) { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }.orEmpty()

    fun epaModels(year: Int, make: String): List<String> =
        query("SELECT DISTINCT model FROM epa_vehicle WHERE year = ? AND make = ? ORDER BY model", arrayOf(year.toString(), make)) { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.orEmpty()

    /** Rows as maps with the same keys as the FuelEconomy.gov web service, so the existing parser is reused. */
    fun epaRows(year: Int, make: String, model: String): List<Map<String, String?>> =
        query("SELECT * FROM epa_vehicle WHERE year = ? AND make = ? AND model = ? ORDER BY id", arrayOf(year.toString(), make, model)) { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }.orEmpty()

    fun epaRow(id: String): Map<String, String?>? = query("SELECT * FROM epa_vehicle WHERE id = ?", arrayOf(id)) { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: Cursor): Map<String, String?> = mapOf(
        "id" to c.str("id"), "year" to c.str("year"), "make" to c.str("make"), "model" to c.str("model"),
        "baseModel" to c.str("base_model"), "displ" to c.str("displ"), "cylinders" to c.str("cylinders"),
        "trany" to c.str("transmission"), "drive" to c.str("drive"), "fuelType1" to c.str("fuel_type"),
        "VClass" to c.str("vclass"), "atvType" to c.str("atv_type"),
        "tCharger" to (if (c.int("turbo") == 1) "T" else null), "sCharger" to (if (c.int("supercharger") == 1) "S" else null),
    )

    private companion object {
        const val TAG = "PenelopeKnowledge"
        const val PACK_PLAIN = "knowledge/penelope_knowledge.db"
        const val PACK_GZ = "knowledge/penelope_knowledge.db.gz"
        const val MANIFEST = "knowledge/knowledge_manifest.json"
    }
}
