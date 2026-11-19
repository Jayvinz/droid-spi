package top.jayvin.android.spi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The public facade must never throw: before [ServiceLoader.initialize] every lookup degrades to
 * `null` / an empty collection (logging once) instead of raising. No device required.
 */
class ServiceLoaderFacadeTest {

    @Before
    fun reset() = ServiceLoader.resetForTest()

    @Test
    fun `lookups before initialize return null or empty instead of throwing`() {
        assertFalse(ServiceLoader.isInitialized())
        assertFacadeEmpty()
    }

    @Test
    fun `initialize without the generated configuration degrades to empty`() {
        ServiceLoader.initialize()

        assertTrue(ServiceLoader.isInitialized())
        assertFacadeEmpty()

        // Repeated initialization is a no-op and still does not throw.
        ServiceLoader.initialize()
        assertTrue(ServiceLoader.isInitialized())
    }

    /** Every public lookup degrades to null / empty instead of throwing while the table is empty. */
    private fun assertFacadeEmpty() {
        assertNull(ServiceLoader.load(Any::class.java))
        assertNull(ServiceLoader.load(Any::class.java, "vip"))
        assertNull(ServiceLoader.loadClass(Any::class.java))
        assertNull(ServiceLoader.providerOf(Any::class.java))
        assertNull(ServiceLoader.findFirst(Any::class.java))
        assertTrue(ServiceLoader.providers(Any::class.java).isEmpty())
        assertTrue(ServiceLoader.stream(Any::class.java).none())
        assertTrue(ServiceLoader.loadAll(Any::class.java).isEmpty())
        assertTrue(ServiceLoader.loadAllClasses(Any::class.java).isEmpty())
        assertNull(ServiceLoader.stateOf(Any::class.java))
        assertTrue(ServiceLoader.snapshot().isEmpty())
    }
}
