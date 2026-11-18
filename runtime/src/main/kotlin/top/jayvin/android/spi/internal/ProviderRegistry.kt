package top.jayvin.android.spi.internal

import top.jayvin.android.spi.Provider
import top.jayvin.android.spi.ProviderConfiguration
import top.jayvin.android.spi.ProviderState
import top.jayvin.android.spi.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider resolver (internal implementation; business code never touches it directly).
 *
 * There is no "pre-scan + cache MISSING" state table. Provider class constants inside
 * `providerOf` / `newProvider` are resolved only when the corresponding branch executes anyway,
 * so probing on demand on every call is enough: after a dynamic module is installed, the next
 * `load()` simply succeeds, with no install event needed to refresh anything.
 *
 * Only `DEAD` is remembered (`<clinit>` failed; the JVM has marked the class erroneous,
 * irreversible in this process) to avoid repeatedly triggering the same error.
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
            "Broken provider configuration: services=${services.size} keys=${keys.size}"
        }
        services.indices.groupBy { services[it] }
            .mapValues { (_, indexes) -> indexes.map { keys[it] }.sorted() }
    }

    private val dead = ConcurrentHashMap.newKeySet<Slot>()

    /** Gets an instance; returns null when not ready, never throws. */
    fun <T : Any> load(service: Class<T>, key: String = ServiceLoader.DEFAULT_KEY): T? {
        instantiate(service, key)?.let { return it }

        // Fallback: no key specified and exactly one provider is available -> use it directly
        if (key == ServiceLoader.DEFAULT_KEY) {
            singleAvailableKey(service)?.let { return instantiate(service, it) }
        }
        return null
    }

    /** Returns the key when exactly one variant is available; 0 or multiple need no fallback, so it returns early on the second. */
    private fun singleAvailableKey(service: Class<*>): String? {
        var found: String? = null
        for (key in keysOf(service)) {
            if (loadClass(service, key) == null) continue
            if (found != null) return null
            found = key
        }
        return found
    }

    /** Class only, without instantiating or triggering the provider's `<clinit>`. */
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

    /** All available variants of the interface. */
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        keysOf(service).forEach { k -> instantiate(service, k)?.let { out[k] = it } }
        return out
    }

    /** All available provider classes of the interface. */
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        keysOf(service).mapNotNull { loadClass(service, it) }

    /** Aligned with `ServiceLoader.stream()`: handles for available providers only. */
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        keysOf(service).mapNotNull { key ->
            loadClass(service, key)?.let { handle(service, key, it) }
        }

    /** Single-entry lookup aligned with `ServiceLoader.iterator()`. */
    fun <T : Any> providerOf(service: Class<T>, key: String): Provider<T>? =
        loadClass(service, key)?.let { handle(service, key, it) }

    /** The handle has already resolved the provider class, so [Provider.type] does not probe again. */
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

    /** Debugging aid: a full snapshot of the current configuration table (re-probed on every call). */
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
            // A <clinit> failure is irreversible; an uninstalled module retries on the next call
            if (e.message?.contains("Could not initialize class") == true) dead += slot
            null
        } catch (_: Throwable) {
            // Business exceptions from the @ProviderFactory body are transient; keep them retryable
            null
        }
    }

    /** Sorted by key at construction; callers do not need to sort again. */
    private fun keysOf(service: Class<*>): List<String> =
        keysByService[service].orEmpty()
}
