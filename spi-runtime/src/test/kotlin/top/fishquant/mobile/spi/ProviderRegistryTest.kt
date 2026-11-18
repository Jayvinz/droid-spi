package top.fishquant.mobile.spi

import top.fishquant.mobile.spi.internal.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不依赖真机，用一个可切换"模块是否已安装"的假配置表来验证状态机：
 * 注册表只做 `providerOf` 探测，模块装好后 `refresh()` 能把它从 MISSING 顶到 READY。
 */
class ProviderRegistryTest {

    private interface Api
    private class Impl : Api
    private class VipImpl : Api

    /** 模拟动态模块：installed=false 时 providerOf 抛 NoClassDefFoundError */
    private class FakeConfiguration(
        var installed: Boolean = false,
        var initFails: Boolean = false,
    ) : ProviderConfiguration {

        var providerOfCalls = 0
        var newProviderCalls = 0

        override fun services(): Array<Class<*>> = arrayOf(Api::class.java, Api::class.java)

        override fun keys(): Array<String> = arrayOf("", "vip")

        override fun providerOf(service: Class<*>, key: String): Class<*>? {
            providerOfCalls++
            if (!installed) throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            return if (key == "vip") VipImpl::class.java else Impl::class.java
        }

        override fun newProvider(service: Class<*>, key: String): Any? {
            newProviderCalls++
            if (!installed) throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            if (initFails) throw ExceptionInInitializerError("boom in <clinit>")
            return if (key == "vip") VipImpl() else Impl()
        }
    }

    @Test
    fun `模块未安装时是 MISSING，安装后 refresh 变 READY`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        registry.refresh()
        assertEquals(ProviderState.MISSING, registry.stateOf(Api::class.java, ""))
        assertNull(registry.load(Api::class.java))

        // 模拟 SplitInstallSessionStatus.INSTALLED
        configuration.installed = true
        registry.refresh()

        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))
        assertTrue(registry.load(Api::class.java) is Impl)
    }

    @Test
    fun `探测阶段不实例化，只有 load 才触发 newProvider`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)
        registry.refresh()

        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))
        assertEquals(0, configuration.newProviderCalls)

        registry.load(Api::class.java)
        assertEquals(1, configuration.newProviderCalls)
    }

    @Test
    fun `repeat refresh 不会重复探测 READY 条目`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)
        registry.refresh()
        val afterFirst = configuration.providerOfCalls

        registry.refresh()
        registry.refresh()

        assertEquals(afterFirst, configuration.providerOfCalls)
    }

    @Test
    fun `clinit 失败后标 DEAD，后续 refresh 不再重试`() {
        val configuration = FakeConfiguration(installed = true, initFails = true)
        val registry = ProviderRegistry(configuration)
        registry.refresh()

        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))

        val callsAfterDeath = configuration.providerOfCalls
        registry.refresh()
        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))
        assertEquals(callsAfterDeath, configuration.providerOfCalls)
    }

    @Test
    fun `未注册的 key 返回 null`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)
        registry.refresh()

        assertNull(registry.load(Api::class.java, "unknown"))
        assertNull(registry.loadClass(Api::class.java, "unknown"))
        assertNull(registry.providerOf(Api::class.java, "unknown"))
    }

    @Test
    fun `stream 只暴露 READY 的 provider 且 type 不触发实例化`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)
        registry.refresh()

        val providers = registry.providers(Api::class.java)
        assertEquals(2, providers.size)
        assertEquals(Impl::class.java, providers[0].type())
        assertEquals(VipImpl::class.java, providers[1].type())
        assertEquals(ProviderState.READY, providers[0].state())
        assertEquals(0, configuration.newProviderCalls)
        assertTrue(providers[0].get() is Impl)
        assertEquals(1, configuration.newProviderCalls)
    }
}
