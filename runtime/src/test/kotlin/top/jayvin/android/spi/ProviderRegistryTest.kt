package top.jayvin.android.spi

import top.jayvin.android.spi.internal.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No device required. A fake configuration table that can toggle "module installed" verifies
 * lazy probing: MISSING is not cached, so the next call after installation naturally succeeds;
 * only `<clinit>` failures are remembered.
 */
class ProviderRegistryTest {

    private interface Api
    private class Impl : Api
    private class OtherImpl : Api
    private class VipImpl : Api

    /** Simulates a dynamic module: when installed=false, providerAt / newProviderAt throw NoClassDefFoundError */
    private class FakeConfiguration(
        var installed: Boolean = false,
        var initFails: Boolean = false,
    ) : ProviderConfiguration {

        var providerAtCalls = 0
        var newProviderAtCalls = 0

        override fun services(): Array<Class<*>> = arrayOf(Api::class.java, Api::class.java)

        override fun keys(): Array<String> = arrayOf("", "vip")

        override fun providerAt(index: Int): Class<*>? {
            providerAtCalls++
            if (!installed) throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            return when (index) {
                0 -> Impl::class.java
                1 -> VipImpl::class.java
                else -> null
            }
        }

        override fun newProviderAt(index: Int): Any? {
            newProviderAtCalls++
            if (!installed) throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            if (initFails) throw ExceptionInInitializerError("boom in <clinit>")
            return when (index) {
                0 -> Impl()
                1 -> VipImpl()
                else -> null
            }
        }
    }

    /** One interface, two default implementations plus one keyed variant. */
    private class MultiDefaultConfiguration(
        var firstDefaultMissing: Boolean = false,
    ) : ProviderConfiguration {

        var newProviderAtCalls = 0

        override fun services(): Array<Class<*>> =
            arrayOf(Api::class.java, Api::class.java, Api::class.java)

        override fun keys(): Array<String> = arrayOf("", "", "vip")

        override fun providerAt(index: Int): Class<*>? {
            if (index == 0 && firstDefaultMissing) {
                throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            }
            return when (index) {
                0 -> Impl::class.java
                1 -> OtherImpl::class.java
                2 -> VipImpl::class.java
                else -> null
            }
        }

        override fun newProviderAt(index: Int): Any? {
            newProviderAtCalls++
            if (index == 0 && firstDefaultMissing) {
                throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            }
            return when (index) {
                0 -> Impl()
                1 -> OtherImpl()
                2 -> VipImpl()
                else -> null
            }
        }
    }

    @Test
    fun `missing until installed, load succeeds without refresh`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.MISSING, registry.stateOf(Api::class.java, ""))

        // Simulates SplitInstallSessionStatus.INSTALLED; no refresh call happens afterwards
        configuration.installed = true

        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))
        assertTrue(registry.load(Api::class.java) is Impl)
    }

    @Test
    fun `load instantiates directly without a pre-probe`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        registry.load(Api::class.java)

        assertEquals(1, configuration.newProviderAtCalls)
        assertEquals(0, configuration.providerAtCalls)
    }

    @Test
    fun `retries every load while uninstalled, no MISSING cache`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertNull(registry.load(Api::class.java))
        assertEquals(2, configuration.newProviderAtCalls)

        configuration.installed = true
        assertTrue(registry.load(Api::class.java) is Impl)
        assertEquals(3, configuration.newProviderAtCalls)
    }

    @Test
    fun `marks DEAD after a clinit failure and stops retrying`() {
        val configuration = FakeConfiguration(installed = true, initFails = true)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))

        val callsAfterDeath = configuration.newProviderAtCalls
        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))
        assertEquals(callsAfterDeath, configuration.newProviderAtCalls)
    }

    @Test
    fun `returns null for an unregistered key`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java, "unknown"))
        assertNull(registry.loadClass(Api::class.java, "unknown"))
        assertNull(registry.providerOf(Api::class.java, "unknown"))
        assertNull(registry.stateOf(Api::class.java, "unknown"))
    }

    @Test
    fun `stream exposes ready providers only and type does not instantiate`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        val providers = registry.providers(Api::class.java)
        assertEquals(2, providers.size)
        assertEquals(Impl::class.java, providers[0].type())
        assertEquals(VipImpl::class.java, providers[1].type())
        assertEquals(ProviderState.READY, providers[0].state())
        assertEquals(0, configuration.newProviderAtCalls)
        assertTrue(providers[0].get() is Impl)
        assertEquals(1, configuration.newProviderAtCalls)
    }

    @Test
    fun `stream picks up an installed module without refresh`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertEquals(0, registry.providers(Api::class.java).size)

        configuration.installed = true
        assertEquals(2, registry.providers(Api::class.java).size)
    }

    @Test
    fun `several default providers are enumerated and load returns the first`() {
        val configuration = MultiDefaultConfiguration()
        val registry = ProviderRegistry(configuration)

        assertTrue(registry.load(Api::class.java) is Impl)

        val providers = registry.providers(Api::class.java)
        assertEquals(3, providers.size)
        assertEquals(Impl::class.java, providers[0].type())
        assertEquals(OtherImpl::class.java, providers[1].type())
        assertEquals(VipImpl::class.java, providers[2].type())

        assertEquals(3, registry.loadAllClasses(Api::class.java).size)
        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))
    }

    @Test
    fun `a missing default falls through to the next default`() {
        val configuration = MultiDefaultConfiguration(firstDefaultMissing = true)
        val registry = ProviderRegistry(configuration)

        assertTrue(registry.load(Api::class.java) is OtherImpl)
        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))

        val all = registry.loadAll(Api::class.java)
        assertTrue(all[""] is OtherImpl)
        assertTrue(all["vip"] is VipImpl)
    }
}
