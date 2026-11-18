package top.jayvin.android.spi

import top.jayvin.android.spi.internal.ProviderRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 不依赖真机，用一个可切换"模块是否已安装"的假配置表来验证懒探测：
 * 未安装时不缓存 MISSING，安装后下一次调用自然就能成功；只有 `<clinit>` 失败会被记住。
 */
class ProviderRegistryTest {

    private interface Api
    private class Impl : Api
    private class VipImpl : Api

    /** 模拟动态模块：installed=false 时 providerOf / newProvider 抛 NoClassDefFoundError */
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
            return when (key) {
                "" -> Impl::class.java
                "vip" -> VipImpl::class.java
                else -> null
            }
        }

        override fun newProvider(service: Class<*>, key: String): Any? {
            newProviderCalls++
            if (!installed) throw NoClassDefFoundError("Failed resolution of: Lcom/example/Impl;")
            if (initFails) throw ExceptionInInitializerError("boom in <clinit>")
            return when (key) {
                "" -> Impl()
                "vip" -> VipImpl()
                else -> null
            }
        }
    }

    @Test
    fun `模块未安装时是 MISSING，安装后无需 refresh 直接 load 成功`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.MISSING, registry.stateOf(Api::class.java, ""))

        // 模拟 SplitInstallSessionStatus.INSTALLED，之后没有任何 refresh 调用
        configuration.installed = true

        assertEquals(ProviderState.READY, registry.stateOf(Api::class.java, ""))
        assertTrue(registry.load(Api::class.java) is Impl)
    }

    @Test
    fun `load 直接实例化，不做预探测`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        registry.load(Api::class.java)

        assertEquals(1, configuration.newProviderCalls)
        assertEquals(0, configuration.providerOfCalls)
    }

    @Test
    fun `未安装时每次 load 都会重新尝试，不缓存 MISSING`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertNull(registry.load(Api::class.java))
        assertEquals(2, configuration.newProviderCalls)

        configuration.installed = true
        assertTrue(registry.load(Api::class.java) is Impl)
        assertEquals(3, configuration.newProviderCalls)
    }

    @Test
    fun `clinit 失败后标 DEAD，后续不再重试`() {
        val configuration = FakeConfiguration(installed = true, initFails = true)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))

        val callsAfterDeath = configuration.newProviderCalls
        assertNull(registry.load(Api::class.java))
        assertEquals(ProviderState.DEAD, registry.stateOf(Api::class.java, ""))
        assertEquals(callsAfterDeath, configuration.newProviderCalls)
    }

    @Test
    fun `未注册的 key 返回 null`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        assertNull(registry.load(Api::class.java, "unknown"))
        assertNull(registry.loadClass(Api::class.java, "unknown"))
        assertNull(registry.providerOf(Api::class.java, "unknown"))
        assertNull(registry.stateOf(Api::class.java, "unknown"))
    }

    @Test
    fun `stream 只暴露可用的 provider 且 type 不实例化`() {
        val configuration = FakeConfiguration(installed = true)
        val registry = ProviderRegistry(configuration)

        val providers = registry.providers(Api::class.java)
        assertEquals(2, providers.size)
        assertEquals(Impl::class.java, providers[0].type())
        assertEquals(VipImpl::class.java, providers[1].type())
        assertEquals(ProviderState.READY, providers[0].state())
        assertEquals(0, configuration.newProviderCalls)
        assertTrue(providers[0].get() is Impl)
        assertEquals(1, configuration.newProviderCalls)
    }

    @Test
    fun `模块安装后 stream 无需 refresh 自动出现`() {
        val configuration = FakeConfiguration(installed = false)
        val registry = ProviderRegistry(configuration)

        assertEquals(0, registry.providers(Api::class.java).size)

        configuration.installed = true
        assertEquals(2, registry.providers(Api::class.java).size)
    }
}
