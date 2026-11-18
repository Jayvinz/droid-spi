package top.fishquant.mobile.spi.internal

import top.fishquant.mobile.spi.Provider
import top.fishquant.mobile.spi.ProviderConfiguration
import top.fishquant.mobile.spi.ProviderState
import top.fishquant.mobile.spi.ServiceLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * provider 状态表（内部实现，业务代码不直接接触）。
 *
 * 每个 `(service, key)` 一个条目，三种状态：
 * - `MISSING`：provider 类当前不可见（动态模块未安装），**可重试**；
 * - `READY`：provider 类可解析；
 * - `DEAD`：provider 的 `<clinit>` 失败，JVM 已把它标记为 erroneous，本进程内不可逆。
 *
 * `refresh()` 只重探 `MISSING` 的条目，`READY` / `DEAD` 原样保留——这是"安装完成后 reinit"
 * 的全部语义：已经就绪的条目不会被重新解析，已死的条目不会被反复触发异常。
 */
internal class ProviderRegistry internal constructor(
    private val configuration: ProviderConfiguration,
) {

    private data class Slot(val service: Class<*>, val key: String) {
        override fun toString(): String = "${service.name}#$key"
    }

    private data class Entry(val state: ProviderState)

    private val entries = AtomicReference<Map<Slot, Entry>>(emptyMap())
    private val lock = Any()

    /**
     * 重探所有未就绪条目。成本是每个 MISSING 条目一次 `ldc` 级别的类解析。
     *
     * 调用时机：初始化时、收到 `SplitInstallSessionStatus.INSTALLED` 时、调试面板手动刷新时。
     */
    fun refresh() {
        synchronized(lock) {
            val prev = entries.get()
            val services = configuration.services()
            val keys = configuration.keys()
            require(services.size == keys.size) {
                "provider configuration 损坏：services=${services.size} keys=${keys.size}"
            }

            val next = HashMap<Slot, Entry>(services.size)
            for (i in services.indices) {
                val slot = Slot(services[i], keys[i])
                val old = prev[slot]
                // READY / DEAD 保留，只重探 MISSING（含首次）
                next[slot] = if (old != null && old.state != ProviderState.MISSING) old else probe(slot)
            }
            entries.set(next)
        }
    }

    private fun probe(slot: Slot): Entry = try {
        if (configuration.providerOf(slot.service, slot.key) == null) {
            Entry(ProviderState.MISSING)
        } else {
            Entry(ProviderState.READY)
        }
    } catch (_: NoClassDefFoundError) {
        // provider 不在当前 dex 中：模块未安装
        Entry(ProviderState.MISSING)
    } catch (_: Throwable) {
        Entry(ProviderState.MISSING)
    }

    /** 取实例；未就绪返回 null，永不抛异常。 */
    fun <T : Any> load(service: Class<T>, key: String = ServiceLoader.DEFAULT_KEY): T? {
        instantiate(service, key)?.let { return it }

        // 降级：未指定 key，且该接口只有一个已就绪 provider 时直接用它
        if (key == ServiceLoader.DEFAULT_KEY) {
            val ready = readyKeys(service)
            if (ready.size == 1) return instantiate(service, ready[0])
        }
        return null
    }

    /** 只取 Class，不实例化，不触发 provider 的 `<clinit>`。 */
    fun <T : Any> loadClass(
        service: Class<T>,
        key: String = ServiceLoader.DEFAULT_KEY,
    ): Class<out T>? {
        if (entries.get()[Slot(service, key)]?.state != ProviderState.READY) return null
        @Suppress("UNCHECKED_CAST")
        return try {
            configuration.providerOf(service, key) as? Class<out T>
        } catch (_: NoClassDefFoundError) {
            null
        }
    }

    /** 该接口下所有已就绪的变体。 */
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        readyKeys(service).forEach { k -> instantiate(service, k)?.let { out[k] = it } }
        return out
    }

    /** 该接口下所有已就绪的 provider 类。 */
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        readyKeys(service).mapNotNull { loadClass(service, it) }

    /** 对齐 `ServiceLoader.stream()`：只返回已就绪 provider 的句柄。 */
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        readyKeys(service).map { handle(service, it) }

    /** 对齐 `ServiceLoader.iterator()` 的单点取用。 */
    fun <T : Any> providerOf(service: Class<T>, key: String): Provider<T>? =
        if (stateOf(service, key) == ProviderState.READY) handle(service, key) else null

    private fun <T : Any> handle(service: Class<T>, key: String): Provider<T> =
        object : Provider<T> {
            override fun type(): Class<out T> {
                @Suppress("UNCHECKED_CAST")
                return try {
                    configuration.providerOf(service, key) as? Class<out T>
                } catch (_: NoClassDefFoundError) {
                    null
                } ?: throw NoClassDefFoundError("provider 已被卸载：$service#$key")
            }

            override fun get(): T? = load(service, key)

            override fun state(): ProviderState =
                stateOf(service, key) ?: ProviderState.MISSING
        }

    fun stateOf(service: Class<*>, key: String): ProviderState? =
        entries.get()[Slot(service, key)]?.state

    /** 调试用：当前配置表的完整快照。 */
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        entries.get().entries
            .sortedWith(compareBy({ it.key.service.name }, { it.key.key }))
            .map { Triple(it.key.service.name, it.key.key, it.value.state) }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> instantiate(service: Class<T>, key: String): T? {
        val slot = Slot(service, key)
        if (entries.get()[slot]?.state != ProviderState.READY) return null

        return try {
            configuration.newProvider(service, key) as? T
        } catch (_: ExceptionInInitializerError) {
            // <clinit> 失败，JVM 已标记 erroneous，本进程内不可逆
            markDead(slot)
            null
        } catch (e: NoClassDefFoundError) {
            when {
                e.message?.contains("Could not initialize class") == true -> markDead(slot)
                // 类又不可见了（模块被卸载）：降回 MISSING，等待下次安装后重探
                e.message?.contains("Failed resolution of") == true -> markMissing(slot)
            }
            null
        } catch (_: Throwable) {
            // @ProviderFactory 方法体内的业务异常：暂时性，保持原状态可重试
            null
        }
    }

    private fun readyKeys(service: Class<*>): List<String> {
        val cur = entries.get()
        return cur.entries
            .filter { it.key.service === service && it.value.state == ProviderState.READY }
            .map { it.key.key }
            .sorted()
    }

    private fun markDead(slot: Slot) {
        synchronized(lock) { entries.updateAndGet { it + (slot to Entry(ProviderState.DEAD)) } }
    }

    private fun markMissing(slot: Slot) {
        synchronized(lock) { entries.updateAndGet { it + (slot to Entry(ProviderState.MISSING)) } }
    }
}
