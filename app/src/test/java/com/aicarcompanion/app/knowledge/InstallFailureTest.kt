package com.aicarcompanion.app.knowledge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aicarcompanion.app.data.knowledge.InstallStage
import com.aicarcompanion.app.data.knowledge.SqliteOfflineKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InstallFailureTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val manifest = """{"database_sha256":"${"ab".repeat(32)}","database_bytes":4}"""

    private fun assertSane(f: com.aicarcompanion.app.data.knowledge.InstallFailure) {
        assertTrue(f.message.length <= 160)
        assertTrue(f.message.all { it.code in 32..126 })
        assertTrue(f.causes.size <= 4 && f.frames.size <= 5)
        assertTrue(f.toString().lines().size == 1)
        assertFalse(f.toString().contains("SECRETBYTES"))
    }

    @Test fun `missing manifest records stage and error without throwing`() {
        val kb = SqliteOfflineKnowledge(context) { throw FileNotFoundException("no ${"x".repeat(500)}é") }
        assertNull(kb.lastFailure)
        assertFalse(kb.install())
        val f = kb.lastFailure!!
        assertEquals(InstallStage.MANIFEST, f.stage)
        assertTrue(f.error.endsWith("FileNotFoundException"))
        assertTrue(f.snapshot.contains("heapMax="))
        assertSane(f)
    }

    @Test fun `checksum mismatch records verify stage, byte counts and no raw bytes`() {
        val kb = SqliteOfflineKnowledge(context) { name ->
            if (name.endsWith(".json")) ByteArrayInputStream(manifest.toByteArray())
            else ByteArrayInputStream("SECRETBYTES".toByteArray())
        }
        assertFalse(kb.install())
        val f = kb.lastFailure!!
        assertEquals(InstallStage.VERIFY, f.stage)
        assertTrue(f.snapshot.contains("source=plain copied=11 manifestBytes=4"))
        assertNotNull(f.message)
        assertSane(f)
        assertFalse(kb.available)
    }
}
