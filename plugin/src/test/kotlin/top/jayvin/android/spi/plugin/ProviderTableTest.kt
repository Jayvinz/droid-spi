package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderTableTest {

    private fun entry(provider: String, key: String = "") =
        ProviderEntry("com.example.Api", provider, key, "")

    @Test
    fun `several default implementations are allowed`() {
        val merged = ProviderTable.merge(
            listOf(entry("com.example.First"), entry("com.example.Second")),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("", ""), merged.map { it.key })
    }

    @Test
    fun `the same default implementation is deduplicated`() {
        val merged = ProviderTable.merge(
            listOf(entry("com.example.First"), entry("com.example.First")),
        )

        assertEquals(1, merged.size)
    }

    @Test
    fun `the same explicit key fails even for one implementation`() {
        val exception = assertThrows(GradleException::class.java) {
            ProviderTable.merge(
                listOf(
                    entry("com.example.First", "vip"),
                    entry("com.example.Second", "vip"),
                ),
            )
        }

        assertEquals(true, exception.message!!.contains("key=\"vip\""))
    }

    @Test
    fun `the same implementation repeated under an explicit key still fails`() {
        assertThrows(GradleException::class.java) {
            ProviderTable.merge(
                listOf(
                    entry("com.example.First", "vip"),
                    entry("com.example.First", "vip"),
                ),
            )
        }
    }

    @Test
    fun `default and keyed entries for the same service coexist`() {
        val merged = ProviderTable.merge(
            listOf(
                entry("com.example.Default"),
                entry("com.example.Vip", "vip"),
            ),
        )

        assertEquals(listOf("", "vip"), merged.map { it.key })
    }
}
