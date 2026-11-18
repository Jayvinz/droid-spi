package top.jayvin.android.spi

import android.util.Log
import top.jayvin.android.spi.generated.ServiceConfiguration
import top.jayvin.android.spi.internal.ProviderRegistry

/**
 * 对外门面，命名与 `java.util.ServiceLoader` 一致。业务代码只接触这个类。
 *
 * ```kotlin
 * val home = ServiceLoader.load(HomeService::class.java)          // 默认 provider
 * val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // key 变体
 * val cam  = ServiceLoader.load<CameraService>()                  // Kotlin 便捷写法
 *
 * ServiceLoader.stream(HomeService::class.java).forEach { provider ->
 *     Log.d("demo", provider.type().name)
 * }
 * ```
 *
 * Java 侧不支持 reified，用 Class 字面量调用即可（所有公开方法都有 `@JvmStatic`）：
 * ```java
 * HomeService home = ServiceLoader.load(HomeService.class);
 * HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
 * for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) { ... }
 * ```
 * Kotlin 侧额外保留 `load<HomeService>()` 内联写法。
 *
 * 与 JDK 的差异（因为 AAB 动态分发）：
 * - [load] 直接返回实例，而 JDK 的 `load()` 返回 loader 对象；
 * - 多了 `key` 变体和 [ProviderState]；
 * - 服务未就绪时返回 null 而不是抛异常。
 */
object ServiceLoader {

    /** 默认实现的 key：空串表示"无 key"，与业务变体标签区分开。 */
    const val DEFAULT_KEY = ""

    @Volatile
    private var registry: ProviderRegistry? = null

    /**
     * 应用启动时调用一次（通常在 `Application.onCreate`）。
     *
     * 直接实例化 app 模块插件生成的 `ServiceConfiguration`；之后业务代码即可调用 [load]。
     * provider 是懒探测的：模块安装完成并且类对当前进程可见后，下一次 `load()` 自然成功。
     */
    @JvmStatic
    fun initialize() {
        synchronized(this) {
            if (registry != null) return
            registry = ProviderRegistry(loadConfiguration())
        }
    }

    @JvmStatic
    fun isInitialized(): Boolean = registry != null

    private fun loadConfiguration(): ProviderConfiguration =
        try {
            val raw: Any = ServiceConfiguration()
            raw as? ProviderConfiguration
                ?: throw IllegalStateException("ServiceConfiguration 未实现 ProviderConfiguration")
        } catch (t: Throwable) {
            Log.e(TAG, "ServiceConfiguration 不可用，ServiceLoader 将返回空数据", t)
            EmptyConfiguration
        }

    /** app 未 apply 插件时的降级配置：所有查询都返回空。 */
    private object EmptyConfiguration : ProviderConfiguration {
        override fun services(): Array<Class<*>> = emptyArray()
        override fun keys(): Array<String> = emptyArray()
        override fun providerOf(service: Class<*>, key: String): Class<*>? = null
        override fun newProvider(service: Class<*>, key: String): Any? = null
    }

    private const val TAG = "AndroidSpi"

    private fun require(): ProviderRegistry =
        registry ?: error(
            "ServiceLoader 尚未初始化：在 Application.onCreate 里调用 " +
                "ServiceLoader.initialize()"
        )

    // ── 取实例 ────────────────────────────────────────────────────
    @JvmStatic
    @JvmOverloads
    fun <T : Any> load(service: Class<T>, key: String = DEFAULT_KEY): T? =
        require().load(service, key)

    /** Kotlin 便捷写法：`load<HomeService>()` / `load<HomeService>("vip")` */
    inline fun <reified T : Any> load(key: String = DEFAULT_KEY): T? = load(T::class.java, key)

    // ── Provider 句柄（对齐 java.util.ServiceLoader.Provider） ────
    @JvmStatic
    @JvmOverloads
    fun <T : Any> providerOf(service: Class<T>, key: String = DEFAULT_KEY): Provider<T>? =
        require().providerOf(service, key)

    /** 取该 service 的第一个 provider（优先默认 key）。 */
    @JvmStatic
    fun <T : Any> findFirst(service: Class<T>): Provider<T>? =
        providerOf(service) ?: stream(service).firstOrNull()

    /** 该 service 下所有已就绪 provider 的惰性序列，对齐 JDK 的 `stream()`。 */
    @JvmStatic
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>> =
        require().providers(service).asSequence()

    /**
     * Java 侧用的等价入口：`Sequence` 在 Java 里不好用，
     * Java 调用方可拿这个 List 再 `.stream()`。
     *
     * ```java
     * ServiceLoader.providers(HomeService.class).stream().forEach(p -> ...);
     * ```
     */
    @JvmStatic
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        require().providers(service)

    // ── 只取 Class，不实例化 ──────────────────────────────────────
    @JvmStatic
    @JvmOverloads
    fun <T : Any> loadClass(service: Class<T>, key: String = DEFAULT_KEY): Class<out T>? =
        require().loadClass(service, key)

    // ── 全量 ──────────────────────────────────────────────────────
    @JvmStatic
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> = require().loadAll(service)

    @JvmStatic
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        require().loadAllClasses(service)

    // ── 状态查询 ──────────────────────────────────────────────────
    @JvmStatic
    @JvmOverloads
    fun stateOf(service: Class<*>, key: String = DEFAULT_KEY): ProviderState? =
        registry?.stateOf(service, key)

    /** 调试用：整张表的状态快照（每次调用都会重新探测）。 */
    @JvmStatic
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        registry?.snapshot() ?: emptyList()
}
