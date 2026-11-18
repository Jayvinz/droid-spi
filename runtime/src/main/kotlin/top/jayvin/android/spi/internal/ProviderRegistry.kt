package top.jayvin.android.spi.internal

import top.jayvin.android.spi.Provider
import top.jayvin.android.spi.ProviderConfiguration
import top.jayvin.android.spi.ProviderState
import top.jayvin.android.spi.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * provider 查询器（内部实现，业务代码不直接接触）。
 *
 * 不做"预扫描 + 缓存 MISSING"的状态表。`providerOf` / `newProvider` 里的 provider 类常量
 * 本来就只在执行到对应分支时才解析，因此每次调用按需探测即可：动态模块装好后，
 * 下一次 `load()` 自然就能成功，不需要安装事件来刷新。
 *
 * 只记住 `DEAD`（`<clinit>` 失败，JVM 已标记 erroneous，本进程内不可逆），避免反复触发同一个错误。
 */
internal class ProviderRegistry internal constructor(
    private val configuration: ProviderConfiguration,
) {

    private data class Slot(val service: Class<*>, val key: String) {
        override fun toString(): String = "${service.name}#$key"
    }

    private val keysByService: Map<Class<*>, List<String>> = run {
        val services = configuration.services()
        val keys = configuration.keys()
        require(services.size == keys.size) {
            "provider configuration 损坏：services=${services.size} keys=${keys.size}"
        }
        services.indices.groupBy { services[it] }
            .mapValues { (_, indexes) -> indexes.map { keys[it] }.sorted() }
    }

    private val dead = ConcurrentHashMap.newKeySet<Slot>()

    /** 取实例；未就绪返回 null，永不抛异常。 */
    fun <T : Any> load(service: Class<T>, key: String = ServiceLoader.DEFAULT_KEY): T? {
        instantiate(service, key)?.let { return it }

        // 降级：未指定 key，且该接口只有一个可用 provider 时直接用它
        if (key == ServiceLoader.DEFAULT_KEY) {
            singleAvailableKey(service)?.let { return instantiate(service, it) }
        }
        return null
    }

    /** 恰好一个可用变体时返回它的 key；0 个或多个都不需要降级，看到第二个就提前返回。 */
    private fun singleAvailableKey(service: Class<*>): String? {
        var found: String? = null
        for (key in keysOf(service)) {
            if (loadClass(service, key) == null) continue
            if (found != null) return null
            found = key
        }
        return found
    }

    /** 只取 Class，不实例化，不触发 provider 的 `<clinit>`。 */
    fun <T : Any> loadClass(
        service: Class<T>,
        key: String = ServiceLoader.DEFAULT_KEY,
    ): Class<out T>? {
        if (Slot(service, key) in dead) return null
        @Suppress("UNCHECKED_CAST")
        return try {
            configuration.providerOf(service, key) as? Class<out T>
        } catch (_: Throwable) {
            null
        }
    }

    /** 该接口下所有可用变体。 */
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        keysOf(service).forEach { k -> instantiate(service, k)?.let { out[k] = it } }
        return out
    }

    /** 该接口下所有可用 provider 类。 */
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        keysOf(service).mapNotNull { loadClass(service, it) }

    /** 对齐 `ServiceLoader.stream()`：只返回可用 provider 的句柄。 */
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        keysOf(service).mapNotNull { key ->
            loadClass(service, key)?.let { handle(service, key, it) }
        }

    /** 对齐 `ServiceLoader.iterator()` 的单点取用。 */
    fun <T : Any> providerOf(service: Class<T>, key: String): Provider<T>? =
        loadClass(service, key)?.let { handle(service, key, it) }

    /** 句柄在取得时已解析出 provider 类，[Provider.type] 不再重复探测。 */
    private fun <T : Any> handle(
        service: Class<T>,
        key: String,
        type: Class<out T>,
    ): Provider<T> =
        object : Provider<T> {
            override fun type(): Class<out T> = type

            override fun get(): T? = load(service, key)

            override fun state(): ProviderState =
                stateOf(service, key) ?: ProviderState.MISSING
        }

    fun stateOf(service: Class<*>, key: String): ProviderState? {
        if (key !in keysOf(service)) return null
        if (Slot(service, key) in dead) return ProviderState.DEAD
        return try {
            if (configuration.providerOf(service, key) == null) {
                ProviderState.MISSING
            } else {
                ProviderState.READY
            }
        } catch (_: Throwable) {
            ProviderState.MISSING
        }
    }

    /** 调试用：当前配置表的完整快照（每次调用都会重新探测）。 */
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        keysByService.entries
            .flatMap { (service, keys) ->
                keys.map { key ->
                    Triple(service.name, key, stateOf(service, key) ?: ProviderState.MISSING)
                }
            }
            .sortedWith(compareBy({ it.first }, { it.second }))

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> instantiate(service: Class<T>, key: String): T? {
        val slot = Slot(service, key)
        if (slot in dead) return null

        return try {
            configuration.newProvider(service, key) as? T
        } catch (_: ExceptionInInitializerError) {
            dead += slot
            null
        } catch (e: NoClassDefFoundError) {
            // <clinit> 失败已不可逆；模块未安装则下次调用再试
            if (e.message?.contains("Could not initialize class") == true) dead += slot
            null
        } catch (_: Throwable) {
            // @ProviderFactory 方法体内的业务异常：暂时性，保持可重试
            null
        }
    }

    /** 构造时已按 key 排序，调用方无需重复排序。 */
    private fun keysOf(service: Class<*>): List<String> =
        keysByService[service].orEmpty()
}
