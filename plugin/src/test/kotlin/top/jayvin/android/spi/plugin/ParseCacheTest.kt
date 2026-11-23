package top.jayvin.android.spi.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParseCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun parsed(text: String): ParsedFile =
        SourceScanner(TypeIndex()).discover(SourceDocument("File.kt", text))

    private fun tempFile(name: String): File = temporaryFolder.newFile("$name.txt")

    @Test
    fun `round-trips entries and resolves identically`() {
        val source = """
            package com.example.impl

            import com.example.api.Home

            @ServiceProvider(service = Home::class, key = "vip")
            class HomeImpl : Home {
                companion object {
                    @ProviderFactory @JvmStatic
                    fun create(): HomeImpl = HomeImpl()
                }
            }
        """.trimIndent()

        val file = tempFile("spi-parse-cache")
        val model = parsed(source)
        val expected = SourceScanner(TypeIndex()).resolveAll(listOf("HomeImpl.kt" to model))

        val hash = ParseCache.sha256(source.toByteArray())
        ParseCache.write(file, mapOf(hash to model))
        val restored = ParseCache.read(file)

        assertEquals(setOf(hash), restored.keys)
        val actual = SourceScanner(TypeIndex())
            .resolveAll(listOf("HomeImpl.kt" to restored.getValue(hash)))
        assertEquals(expected, actual)
        assertEquals("vip", expected.single().key)
        assertEquals("create", expected.single().factory)
    }

    @Test
    fun `a file without entries round-trips`() {
        // Its record has an empty entries column; the column count must still be stable.
        val source = "package com.example\n\nclass Plain\n"
        val file = tempFile("spi-parse-cache-plain")
        val model = parsed(source)

        ParseCache.write(file, mapOf(ParseCache.sha256(source.toByteArray()) to model))
        val restored = ParseCache.read(file).values.single()

        assertEquals(model.packageName, restored.packageName)
        assertEquals(model.declaredTypes, restored.declaredTypes)
        assertTrue(restored.entries.isEmpty())
    }

    @Test
    fun `a missing or malformed cache is ignored`() {
        assertTrue(ParseCache.read(File(temporaryFolder.root, "missing.txt")).isEmpty())

        val file = tempFile("spi-parse-cache-bad")
        file.writeText("droid-spi-parse-cache-v3\nnot-a-record\n")
        assertTrue(ParseCache.read(file).isEmpty())

        val stale = tempFile("spi-parse-cache-stale")
        stale.writeText("some other format\n")
        assertTrue(ParseCache.read(stale).isEmpty())
    }

    @Test
    fun `identical content produces the same key`() {
        val a = "package com.example\n\nclass Same\n"
        val b = "package com.example\n\nclass Same\n"

        assertEquals(ParseCache.sha256(a.toByteArray()), ParseCache.sha256(b.toByteArray()))
    }
}
