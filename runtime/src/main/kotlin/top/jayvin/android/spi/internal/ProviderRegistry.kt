package top.jayvin.android.spi.internal

import top.jayvin.android.spi.Provider
import top.jayvin.android.spi.ProviderConfiguration
import top.jayvin.android.spi.ProviderState
import top.jayvin.android.spi.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider resolver (internal implementation; business code never touches it directly).
 *
 * The configuration table is index-addressable: `services()` / `keys()` are one-to-one, and
 * `providerAt` / `newProviderAt` take the index. That is what allows several default
 * implementations (same interface, empty key) to coexist: enumeration walks every index, while
 * key-based lookups take the first available entry for that key.
 *
 * There is no "pre-scan + cache MISSING" state table. Provider class constants are resolved only
 * when the corresponding entry is probed, so probing on demand on every call is enough: after a
 * dynamic module is installed, the next `load()` simply succeeds, with no install event needed
 * to refresh anything.
 *
 * Only `DEAD` is remembered (`<clinit>` failed; the JVM has marked the class erroneous,
 * irreversible in this process) to avoid repeatedly triggering the same error. It is tracked per
 * entry index, not per key, because the default key may own several entries.
 */
internal class ProviderRegistry internal constructor(
    private val configuration: ProviderConfiguration,
) {

    private class Entry(val service: Class<*>, val key: String, val index: Int)

    private val entries: List<Entry> = run {
        val services = configuration.services()
        val keys = configuration.keys()
        require(services.size == keys.size) {
            "Broken provider configuration: services=${services.size} keys=${keys.size}"
        }
        services.indices.map { Entry(services[it], keys[it], it) }
    }

    private val entriesByService: Map<Class<*>, List<Entry>> = entries.groupBy { it.service }

    private val dead = ConcurrentHashMap.newKeySet<Int>()

    /** Gets an instance; returns null when not ready, never throws. */
    fun <T : Any> load(service: Class<T>, key: String = ServiceLoader.DEFAULT_KEY): T? {
        for (entry in entriesFor(service, key)) {
            val instance: T? = instantiate(entry)
            if (instance != null) return instance
        }

        // Fallback: no key specified and exactly one non-default key is available -> use it
        if (key == ServiceLoader.DEFAULT_KEY) {
            singleAvailableKey(service)?.let { return load(service, it) }
        }
        return null
    }

    /**
     * Returns the only available non-default key, or null when there are none or several.
     * Several default implementations are not a fallback candidate: the default key was already
     * tried above and had nothing available.
     */
    private fun singleAvailableKey(service: Class<*>): String? {
        val available = entriesFor(service)
            .asSequence()
            .filter { it.key != ServiceLoader.DEFAULT_KEY && providerClass(it) != null }
            .map { it.key }
            .distinct()
            .toList()
        return available.singleOrNull()
    }

    /** Class only, without instantiating or triggering the provider's `<clinit>`. */
    fun <T : Any> loadClass(
        service: Class<T>,
        key: String = ServiceLoader.DEFAULT_KEY,
    ): Class<out T>? =
        entriesFor(service, key).firstNotNullOfOrNull { entry ->
            @Suppress("UNCHECKED_CAST")
            providerClass(entry) as? Class<out T>
        }

    /**
     * All available variants of the interface, keyed by variant label.
     *
     * The default key can have several implementations; this map keeps the first available one
     * for each key. Use [providers] or [loadAllClasses] to enumerate every entry.
     */
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for (entry in entriesFor(service)) {
            if (entry.key in out) continue
            val instance: T? = instantiate(entry)
            if (instance != null) out[entry.key] = instance
        }
        return out
    }

    /** All available provider classes, including every entry under the default key. */
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        entriesFor(service).mapNotNull { entry ->
            @Suppress("UNCHECKED_CAST")
            providerClass(entry) as? Class<out T>
        }

    /** Aligned with `ServiceLoader.stream()`: handles for every available entry, defaults included. */
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        entriesFor(service).mapNotNull { entry ->
            providerClass(entry)?.let { handle(entry, it) }
        }

    /** First available entry for `(service, key)`; multiple defaults yield the first ready one. */
    fun <T : Any> providerOf(service: Class<T>, key: String): Provider<T>? =
        entriesFor(service, key).firstNotNullOfOrNull { entry ->
            providerClass(entry)?.let { handle(entry, it) }
        }

    /** The handle already resolved the provider class, so [Provider.type] does not probe again. */
    private fun <T : Any> handle(
        entry: Entry,
        type: Class<*>,
    ): Provider<T> =
        object : Provider<T> {
            @Suppress("UNCHECKED_CAST")
            override fun type(): Class<out T> = type as Class<out T>

            override fun get(): T? = instantiate(entry)

            override fun state(): ProviderState = entryState(entry)
        }

    /**
     * Returns the current state of `(service, key)`, or null when the pair is not registered.
     * For several default entries the states are aggregated: any READY wins, then any MISSING;
     * DEAD only when every matching entry is dead.
     */
    fun stateOf(service: Class<*>, key: String): ProviderState? {
        val matching = entriesFor(service, key)
        if (matching.isEmpty()) return null
        val states = matching.map { entryState(it) }
        return when {
            ProviderState.READY in states -> ProviderState.READY
            ProviderState.MISSING in states -> ProviderState.MISSING
            else -> ProviderState.DEAD
        }
    }

    /** Debugging aid: a full snapshot of the current configuration table (re-probed on every call). */
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        entries
            .map { Triple(it.service.name, it.key, entryState(it)) }
            .sortedWith(compareBy({ it.first }, { it.second }))

    /** Instantiates the provider, applying the DEAD and retry rules. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> instantiate(entry: Entry): T? {
        if (entry.index in dead) return null

        return try {
            configuration.newProviderAt(entry.index) as? T
        } catch (_: ExceptionInInitializerError) {
            dead += entry.index
            null
        } catch (e: NoClassDefFoundError) {
            // A <clinit> failure is irreversible; an uninstalled module retries on the next call
            if (e.message?.contains("Could not initialize class") == true) dead += entry.index
            null
        } catch (_: Throwable) {
            // Business exceptions from the @ProviderFactory body are transient; keep them retryable
            null
        }
    }

    /** Resolves the entry's class without triggering `<clinit>`; any failure means unavailable. */
    private fun providerClass(entry: Entry): Class<*>? {
        if (entry.index in dead) return null
        return try {
            configuration.providerAt(entry.index)
        } catch (_: Throwable) {
            null
        }
    }

    private fun entryState(entry: Entry): ProviderState = when {
        entry.index in dead -> ProviderState.DEAD
        providerClass(entry) != null -> ProviderState.READY
        else -> ProviderState.MISSING
    }

    private fun entriesFor(service: Class<*>, key: String): List<Entry> =
        entriesByService[service].orEmpty().filter { it.key == key }

    private fun entriesFor(service: Class<*>): List<Entry> =
        entriesByService[service].orEmpty()
}
