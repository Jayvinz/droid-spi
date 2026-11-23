package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceScannerTest {

    private fun scan(vararg files: Pair<String, String>): List<ProviderEntry> {
        val index = TypeIndex()
        val documents = files.map { (name, text) ->
            SourceDocument(name, text).also { SourceScanner.collectTypes(it.masked, index) }
        }
        val scanner = SourceScanner(index)
        return documents.flatMap { scanner.scan(it) }
    }

    private fun entry(service: String, provider: String, key: String = "", factory: String = "") =
        ProviderEntry(service, provider, key, factory)

    @Test
    fun `explicit import and named argument are resolved`() {
        val entries = scan(
            "HomeImpl.kt" to """
                package com.example.impl

                import com.example.api.Home

                @ServiceProvider(service = Home::class)
                class HomeImpl : Home
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `import alias is resolved`() {
        val entries = scan(
            "HomeImpl.kt" to """
                package com.example.impl

                import com.example.api.Home as ApiHome

                @ServiceProvider(service = ApiHome::class)
                class HomeImpl : ApiHome
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `wildcard import falls back to the module-wide type index`() {
        val entries = scan(
            "Home.kt" to """
                package com.example.api

                interface Home
            """.trimIndent(),
            "HomeImpl.kt" to """
                package com.example.impl

                import com.example.api.*

                @ServiceProvider(service = Home::class)
                class HomeImpl : Home
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `wildcard import with an unknown type fails the build`() {
        val exception = assertThrows(GradleException::class.java) {
            scan(
                "HomeImpl.kt" to """
                    package com.example.impl

                    import com.thirdparty.*

                    @ServiceProvider(service = Home::class)
                    class HomeImpl
                """.trimIndent(),
            )
        }

        assertEquals(true, exception.message!!.contains("cannot determine the FQN"))
    }

    @Test
    fun `Java class literal and static import are accepted`() {
        val entries = scan(
            "HomeImpl.java" to """
                package com.example.impl;

                import com.example.api.Home;
                import static com.example.api.Util.NAME;

                @ServiceProvider(Home.class)
                public class HomeImpl implements Home { }
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `key and factory are parsed`() {
        val entries = scan(
            "HomeImpl.kt" to """
                package com.example.impl

                import com.example.api.Home

                @ServiceProvider(service = Home::class, key = "vip")
                class HomeImpl : Home {
                    companion object {
                        @ProviderFactory @JvmStatic
                        fun create(): HomeImpl = HomeImpl()
                    }
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl", "vip", "create")),
            entries,
        )
    }

    @Test
    fun `imports preceding a top-level property are still parsed`() {
        val entries = scan(
            "HomeImpl.kt" to """
                package com.example.impl

                import com.example.api.Home

                private const val NAME = "home"

                @ServiceProvider(service = Home::class)
                class HomeImpl : Home
            """.trimIndent(),
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `CRLF sources are handled`() {
        val entries = scan(
            "HomeImpl.kt" to
                "package com.example.impl\r\n" +
                "import com.example.api.Home\r\n\r\n" +
                "@ServiceProvider(service = Home::class)\r\n" +
                "class HomeImpl : Home\r\n",
        )

        assertEquals(
            listOf(entry("com.example.api.Home", "com.example.impl.HomeImpl")),
            entries,
        )
    }

    @Test
    fun `annotation text inside comments and strings is ignored`() {
        val entries = scan(
            "Plain.kt" to """
                package com.example.impl

                // @ServiceProvider(service = Home::class)
                val text = "@ServiceProvider(service = Home::class)"
            """.trimIndent(),
        )

        assertEquals(emptyList<ProviderEntry>(), entries)
    }

    @Test
    fun `a file without the annotation is skipped`() {
        val entries = scan(
            "Plain.kt" to """
                package com.example.impl

                class Plain
            """.trimIndent(),
        )

        assertEquals(emptyList<ProviderEntry>(), entries)
    }
}
