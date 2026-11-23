package top.jayvin.android.spi.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTextTest {

    private fun process(src: String) = SourceText.process(src)

    private fun assertOffsetsPreserved(src: String) {
        val processed = process(src)
        assertEquals(src.length, processed.clean.length)
        assertEquals(src.length, processed.masked.length)
        assertEquals(
            src.count { it == '\n' },
            processed.clean.count { it == '\n' },
        )
        assertEquals(
            src.count { it == '\n' },
            processed.masked.count { it == '\n' },
        )
    }

    @Test
    fun `line comment becomes spaces and keeps the newline`() {
        val src = "a //x\nb"
        val processed = process(src)

        assertEquals("a    \nb", processed.clean)
        assertEquals(processed.clean, processed.masked)
        assertOffsetsPreserved(src)
    }

    @Test
    fun `block comment becomes spaces and keeps its newlines`() {
        val src = "a /*x*/ b"
        val processed = process(src)

        assertEquals("a" + " ".repeat(7) + "b", processed.clean)
        assertEquals(processed.clean, processed.masked)
        assertOffsetsPreserved(src)

        val multiline = process("a /*\n*/ b")
        assertEquals("a   \n   b", multiline.clean)
        assertOffsetsPreserved("a /*\n*/ b")
    }

    @Test
    fun `string contents are kept in clean and masked in masked`() {
        val src = "s = \"class X\""
        val processed = process(src)

        assertEquals(src, processed.clean)
        assertFalse(processed.masked.contains("class"))
        assertFalse(processed.masked.contains("\""))
        assertEquals(src.substring(0, 4), processed.masked.substring(0, 4))
        assertOffsetsPreserved(src)
    }

    @Test
    fun `escaped quotes do not close the string`() {
        val src = "s = \"a \\\"class X\\\" b\""
        val processed = process(src)

        assertEquals(src, processed.clean)
        assertFalse(processed.masked.contains("class"))
        assertFalse(processed.masked.contains("X"))
        assertOffsetsPreserved(src)
    }

    @Test
    fun `raw string contents and delimiters are masked`() {
        val src = "s = " + "\"\"\"" + "class X\nY" + "\"\"\""
        val processed = process(src)

        assertEquals(src, processed.clean)
        assertFalse(processed.masked.contains("class"))
        assertFalse(processed.masked.contains("\""))
        assertEquals(1, processed.masked.count { it == '\n' })
        assertOffsetsPreserved(src)
    }

    @Test
    fun `char literals are masked`() {
        val src = "val c = 'x'"
        val processed = process(src)

        assertEquals(src, processed.clean)
        assertEquals(' ', processed.masked[9])
        assertOffsetsPreserved(src)
    }

    @Test
    fun `comment markers inside strings do not start comments`() {
        val src = "s = \"http://x\" // real"
        val processed = process(src)

        assertTrue(processed.clean.contains("http://x"))
        assertFalse(processed.clean.contains("real"))
        assertFalse(processed.masked.contains("http"))
        assertOffsetsPreserved(src)
    }

    @Test
    fun `unterminated string is masked to the end`() {
        val src = "s = \"class X"
        val processed = process(src)

        assertEquals(src, processed.clean)
        assertFalse(processed.masked.contains("class"))
        assertOffsetsPreserved(src)
    }

    @Test
    fun `comments and strings in a realistic file are separated correctly`() {
        val src = listOf(
            "package com.example",
            "",
            "/** KDoc with class Fake */",
            "@Ann(\"str // not a comment\")",
            "class Real { val r = " + "\"\"\"" + "raw class Y" + "\"\"\"" + " }",
        ).joinToString("\n")

        val processed = process(src)

        assertTrue(processed.clean.contains("class Real"))
        assertFalse(processed.clean.contains("class Fake"))
        assertTrue(processed.clean.contains("str // not a comment"))
        assertTrue(processed.clean.contains("raw class Y"))

        assertTrue(processed.masked.contains("class Real"))
        assertFalse(processed.masked.contains("Fake"))
        assertFalse(processed.masked.contains("not a comment"))
        assertFalse(processed.masked.contains("raw class Y"))
        assertOffsetsPreserved(src)
    }

    @Test
    fun `CRLF line endings preserve offsets and newlines`() {
        val src = "a //x\r\nb\r\nclass Real"
        val processed = process(src)

        assertTrue(processed.masked.contains("class Real"))
        assertOffsetsPreserved(src)
    }
}
