package com.aicarcompanion.app.knowledge

import com.aicarcompanion.app.data.knowledge.InstallStage
import com.aicarcompanion.app.data.knowledge.KnowledgePackInstaller
import com.aicarcompanion.app.data.knowledge.KnowledgePackInstaller.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Plain-JVM tests of the knowledge-pack file handling, with tiny fake packs. */
class KnowledgePackInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val header = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun pack(seed: Int, size: Int = 4096): ByteArray = header + ByteArray(size) { (it * 31 + seed).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun File.packs() = listFiles().orEmpty().filter { it.name.startsWith("penelope_knowledge_") && it.name.endsWith(".db") }
    private fun File.temps() = listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

    private fun installer(
        dir: File,
        bytes: ByteArray,
        declaredBytes: Long = bytes.size.toLong(),
        opened: AtomicInteger = AtomicInteger(),
        open: () -> InputStream = { opened.incrementAndGet(); ByteArrayInputStream(bytes) },
    ) = KnowledgePackInstaller(dir, sha(bytes), declaredBytes, open)

    @Test fun `installs, verifies and keeps exactly the target file`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(1)
        val out = installer(dir, bytes).install()
        val ready = out as Outcome.Ready
        assertEquals(sha(bytes).take(12), ready.target.name.removePrefix("penelope_knowledge_").removeSuffix(".db"))
        assertTrue(ready.target.readBytes().contentEquals(bytes))
        assertEquals(listOf(ready.target), dir.listFiles().orEmpty().toList())
    }

    @Test fun `eight threads times 25 rounds sharing one folder all end Ready with one file and no temp`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(2, size = 20_000)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(25) { round ->
                // Fresh state each round so the install path (not just reuse) is raced.
                dir.listFiles().orEmpty().forEach { it.delete() }
                val start = CountDownLatch(1)
                val results = (0 until threads).map {
                    pool.submit<Outcome> { start.await(); installer(dir, bytes).install() }
                }
                start.countDown()
                results.forEach {
                    val o = it.get(60, TimeUnit.SECONDS)
                    assertTrue("round $round: $o", o is Outcome.Ready)
                }
                assertEquals("round $round", 1, dir.packs().size)
                assertTrue("round $round temps: ${dir.temps()}", dir.temps().isEmpty())
                assertEquals(sha(bytes), sha(dir.packs().single().readBytes()))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `a truncated target is replaced`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(3)
        val inst = installer(dir, bytes)
        File(dir, inst.target.name).writeBytes(bytes.copyOf(100))
        val opened = AtomicInteger()
        val out = installer(dir, bytes, opened = opened).install()
        assertTrue(out is Outcome.Ready)
        assertEquals(1, opened.get())
        assertTrue(inst.target.readBytes().contentEquals(bytes))
    }

    @Test fun `a same-size corrupt target is replaced when the database check rejects it`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(4)
        val probe = installer(dir, bytes)
        File(dir, probe.target.name).writeBytes(ByteArray(bytes.size)) // right size, no SQLite header
        val opened = AtomicInteger()
        val out = KnowledgePackInstaller(dir, sha(bytes), bytes.size.toLong(), { opened.incrementAndGet(); ByteArrayInputStream(bytes) }).install()
        assertTrue(out is Outcome.Ready)
        assertEquals(1, opened.get())
        assertTrue(probe.target.readBytes().contentEquals(bytes))
    }

    @Test fun `a manifest without database_bytes skips only the size check`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(5)
        val opened = AtomicInteger()
        assertTrue(installer(dir, bytes, declaredBytes = -1, opened = opened).install() is Outcome.Ready)
        assertTrue(installer(dir, bytes, declaredBytes = -1, opened = opened).install() is Outcome.Ready)
        assertEquals("second run reuses the verified file", 1, opened.get())
    }

    @Test fun `stale temp files are removed`() {
        val dir = tmp.newFolder("k")
        File(dir, "installing.tmp").writeBytes(ByteArray(10))
        File(dir, "installing-123.tmp").writeBytes(ByteArray(10))
        assertTrue(installer(dir, pack(6)).install() is Outcome.Ready)
        assertTrue(dir.temps().isEmpty())
    }

    @Test fun `a restart reuses the verified file without opening the pack or touching it`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(7)
        val first = (installer(dir, bytes).install() as Outcome.Ready).target
        first.setLastModified(1_000_000_000_000L)
        val stamp = first.lastModified()
        val opened = AtomicInteger()
        val second = installer(dir, bytes, opened = opened).install()
        assertEquals(Outcome.Ready(first), second)
        assertEquals(0, opened.get())
        assertEquals(stamp, first.lastModified())
    }

    @Test fun `reusing a verified target also removes an old pack left by an interrupted update`() {
        val dir = tmp.newFolder("k")
        val old = pack(3)
        val oldTarget = (installer(dir, old).install() as Outcome.Ready).target
        // The new pack was installed, then the app was killed before the old one (and its side files) was deleted.
        val fresh = pack(4)
        val current = (installer(dir, fresh).install() as Outcome.Ready).target
        oldTarget.writeBytes(old)
        File(dir, oldTarget.name + "-journal").writeBytes(ByteArray(3))
        File(dir, oldTarget.name + "-wal").writeBytes(ByteArray(3))
        File(dir, oldTarget.name + "-shm").writeBytes(ByteArray(3))
        current.setLastModified(1_000_000_000_000L)
        val stamp = current.lastModified()
        val opened = AtomicInteger()
        val again = installer(dir, fresh, opened = opened).install()
        assertEquals(Outcome.Ready(current), again)
        assertEquals(0, opened.get())
        assertEquals(stamp, current.lastModified())
        assertEquals(listOf(current), dir.listFiles().orEmpty().toList())
    }

    @Test fun `an update replaces older packs only after the new one is verified`() {
        val dir = tmp.newFolder("k")
        val old = pack(8)
        val oldTarget = (installer(dir, old).install() as Outcome.Ready).target
        File(dir, oldTarget.name + "-journal").writeBytes(ByteArray(3))
        val fresh = pack(9)
        val out = installer(dir, fresh).install() as Outcome.Ready
        assertFalse(oldTarget.exists())
        assertFalse(File(dir, oldTarget.name + "-journal").exists())
        assertEquals(listOf(out.target), dir.listFiles().orEmpty().toList())
    }

    @Test fun `a mismatching update fails at VERIFY, leaves the old pack and no temp`() {
        val dir = tmp.newFolder("k")
        val old = pack(10)
        val oldTarget = (installer(dir, old).install() as Outcome.Ready).target
        val oldStamp = oldTarget.lastModified()
        val wanted = pack(11)
        val wrongBytes = pack(12) // same length, different content
        val out = KnowledgePackInstaller(dir, sha(wanted), wanted.size.toLong(), { ByteArrayInputStream(wrongBytes) }).install()
        val failed = out as Outcome.Failed
        assertEquals(InstallStage.VERIFY, failed.stage)
        assertTrue(oldTarget.exists() && oldTarget.readBytes().contentEquals(old))
        assertEquals(oldStamp, oldTarget.lastModified())
        assertTrue(dir.temps().isEmpty())
        assertEquals(listOf(oldTarget), dir.listFiles().orEmpty().toList())
    }

    @Test fun `a throwing source reports stage, copied count and leaves no temp`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(13)
        val stages = mutableListOf<InstallStage>()
        val inst = KnowledgePackInstaller(
            dir, sha(bytes), bytes.size.toLong(),
            openPack = {
                object : InputStream() {
                    var served = 0
                    override fun read(): Int = throw IOException("disk gone")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served >= 100) throw IOException("disk gone")
                        val n = minOf(len, 100 - served)
                        served += n
                        return n
                    }
                }
            },
            onStage = { stages += it },
        )
        val failed = inst.install() as Outcome.Failed
        assertEquals(InstallStage.COPY, failed.stage)
        assertTrue(failed.error is IOException)
        assertEquals(100L, inst.copied)
        assertNotNull(inst.temp)
        assertTrue(dir.temps().isEmpty())
        assertTrue(dir.packs().isEmpty())
        assertTrue(stages.containsAll(listOf(InstallStage.DIR, InstallStage.SOURCE_OPEN, InstallStage.COPY)))
    }

    @Test fun `a source that fails to open reports SOURCE_OPEN`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(14)
        val failed = KnowledgePackInstaller(dir, sha(bytes), bytes.size.toLong(), { throw IOException("missing") }).install() as Outcome.Failed
        assertEquals(InstallStage.SOURCE_OPEN, failed.stage)
        assertTrue(dir.temps().isEmpty())
    }

    @Test fun `an OutOfMemoryError propagates and still leaves no temp file`() {
        val dir = tmp.newFolder("k")
        val bytes = pack(15)
        val inst = KnowledgePackInstaller(dir, sha(bytes), bytes.size.toLong(), { throw OutOfMemoryError("Java heap space") })
        assertThrows(OutOfMemoryError::class.java) { inst.install() }
        assertTrue(dir.temps().isEmpty())
        assertEquals(InstallStage.SOURCE_OPEN, inst.stage)
        // The process-wide lock must have been released: a later install works.
        assertTrue(installer(dir, bytes).install() is Outcome.Ready)
    }
}
