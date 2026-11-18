package com.example.spi

import android.content.Context
import com.example.spi.internal.InstallWatcher
import com.example.spi.internal.ProviderRegistry

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

    /** 默认实现的 key。用空串而非哨兵，因为 key 的语义是"业务变体标签"。 */
    const val DEFAULT_KEY = ""

    @Volatile
    private var registry: ProviderRegistry? = null

    private var watcher: InstallWatcher? = null

    /**
     * 由生成的 [ServiceLoaderInitializer] 在 ContentProvider.onCreate 中调用，
     * 发生在 `Application.onCreate` 之前，业务代码无需自己初始化。
     */
    @JvmStatic
    fun initialize(context: Context, configuration: ProviderConfiguration) {
        synchronized(this) {
            if (registry != null) return

            val appContext = context.applicationContext ?: context
            InstallWatcher.ensureSplitCompat(appContext)

            val reg = ProviderRegistry(configuration)
            // 先挂监听再首扫：否则"首扫 → 挂监听"之间完成的安装会被漏掉
            InstallWatcher.create(appContext)?.let { w ->
                w.attach { reg.refresh() }
                watcher = w
            }
            reg.refresh()
            registry = reg
        }
    }

    @JvmStatic
    fun isInitialized(): Boolean = registry != null

    private fun require(): ProviderRegistry =
        registry ?: error(
            "ServiceLoader 尚未初始化：检查 AndroidManifest 是否声明了 " +
                "com.example.spi.generated.GeneratedServiceConfiguration 这个 <provider>"
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

    // ── 动态模块联动 ──────────────────────────────────────────────
    /**
     * 重探所有未就绪条目。安装完成的自动路径由 [InstallWatcher] 触发，
     * 这里只留给调试面板、或接入方自定义的安装通道（如自研插件化）使用。
     */
    @JvmStatic
    fun refresh() {
        registry?.refresh()
    }

    @JvmStatic
    @JvmOverloads
    fun stateOf(service: Class<*>, key: String = DEFAULT_KEY): ProviderState? =
        registry?.stateOf(service, key)

    /** 调试用：整张表的状态快照。 */
    @JvmStatic
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        registry?.snapshot() ?: emptyList()
}
