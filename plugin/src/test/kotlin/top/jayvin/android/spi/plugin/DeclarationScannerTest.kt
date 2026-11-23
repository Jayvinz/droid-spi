package top.jayvin.android.spi.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarationScannerTest {

    private fun names(src: String): List<String> {
        val out = ArrayList<String>()
        DeclarationScanner.forEach(src) { out += it.name }
        return out
    }

    @Test
    fun `finds class, object and interface names`() {
        assertEquals(
            listOf("A", "B", "C"),
            names("class A\nobject B\ninterface C"),
        )
    }

    @Test
    fun `reports the keyword start and the end of the name`() {
        val declaration = DeclarationScanner.find("class Real")

        assertEquals(0, declaration!!.start)
        assertEquals(10, declaration.end)
        assertEquals("Real", declaration.name)
    }

    @Test
    fun `ignores keywords inside identifiers`() {
        assertEquals(listOf("Real"), names("myclass Fake\nclass Real"))
    }

    @Test
    fun `requires whitespace and an identifier after the keyword`() {
        assertEquals(listOf("Foo", "Bar", "Baz"), names("class\nFoo\nobject\tBar\ninterface Baz"))
        assertTrue(names("class").isEmpty())
        assertTrue(names("class   ").isEmpty())
        assertTrue(names("class 123").isEmpty())
    }

    @Test
    fun `java annotation declarations match like the old regex`() {
        assertEquals(listOf("Ann"), names("@interface Ann"))
    }

    @Test
    fun `identifiers are ascii-only, like the old regex`() {
        assertTrue(names("class Ä").isEmpty())
        assertEquals(listOf("A_b1"), names("class A_b1"))
    }

    @Test
    fun `find starts at the given offset`() {
        val src = "class A\nclass B"

        assertEquals("A", DeclarationScanner.find(src)!!.name)
        assertEquals("B", DeclarationScanner.find(src, 1)!!.name)
        assertNull(DeclarationScanner.find(src, 16))
    }

    @Test
    fun `nested declarations are found, matching the old behavior`() {
        assertEquals(listOf("Outer", "Inner"), names("class Outer {\n    class Inner\n}"))
    }

    @Test
    fun `keyword prefixes are not declarations`() {
        assertTrue(names("classy Foo").isEmpty())
        assertTrue(names("objects Foo").isEmpty())
        assertTrue(names("interfaces Foo").isEmpty())
    }
}
