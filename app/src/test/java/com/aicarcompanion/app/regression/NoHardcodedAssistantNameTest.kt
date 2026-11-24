package com.aicarcompanion.app.regression

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner decision 2026-11-24: the app isn't officially named and the assistant's name is whatever the user
 * chooses, so no user-visible text may hardcode "Penelope". Text from the assistant is first person or takes
 * the current name; text about the app says "this app".
 */
class NoHardcodedAssistantNameTest {
    private val mainDir = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    /** String literals that may contain the word: the default profile definition and the default-name constant. */
    private val allowedFiles = setOf("AssistantProfile.kt")
    private fun allowedLine(line: String) = "TAG" in line || "DEFAULT_ASSISTANT_NAME" in line

    private val literal = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
    private fun hasPenelopeLiteral(line: String) = literal.findAll(line).any { "Penelope" in it.value }

    @Test fun `no Kotlin string literal in app main hardcodes Penelope`() {
        val offenders = File(mainDir, "java").walkTopDown().filter { it.extension == "kt" && it.name !in allowedFiles }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                if (allowedLine(line)) return@mapIndexedNotNull null
                if (hasPenelopeLiteral(line)) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue("User-visible text must not hardcode the assistant name: $offenders", offenders.isEmpty())
    }

    @Test fun `no resource string hardcodes Penelope`() {
        val offenders = File(mainDir, "res").walkTopDown().filter { it.extension == "xml" && "values" in it.parentFile.name }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.contains("Penelope") && !line.trim().startsWith("<!--")) "${f.name}:${i + 1}" else null }
        }.toList()
        assertTrue("Resource strings must not hardcode the assistant name: $offenders", offenders.isEmpty())
    }
}
