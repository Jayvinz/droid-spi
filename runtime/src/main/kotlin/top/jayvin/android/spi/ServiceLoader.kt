package top.jayvin.android.spi

import android.util.Log
import top.jayvin.android.spi.generated.ServiceConfiguration
import top.jayvin.android.spi.internal.ProviderRegistry

/**
 * Public facade, named after `java.util.ServiceLoader`. Business code only touches this class.
 *
 * ```kotlin
 * val home = ServiceLoader.load(HomeService::class.java)          // default provider
 * val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // key variant
 * val cam  = ServiceLoader.load<CameraService>()                  // Kotlin convenience form
 *
 * ServiceLoader.stream(HomeService::class.java).forEach { provider ->
 *     Log.d("demo", provider.type().name)
 * }
 * ```
 *
 * Java has no reified generics, so pass Class literals (every public method is `@JvmStatic`):
 * ```java
 * HomeService home = ServiceLoader.load(HomeService.class);
 * HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
 * for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) { ... }
 * ```
 * Kotlin additionally keeps the inline `load<HomeService>()` form.
 *
 * Differences from the JDK (because of AAB dynamic delivery):
 * - [load] returns an instance directly, whereas the JDK's `load()` returns a loader object;
 * - there is an extra `key` variant and [ProviderState];
 * - when a service is not ready, it returns null instead of throwing.
 *
 * The facade never throws: the configuration is loaded lazily on the first lookup (or eagerly via
 * [initialize]), and a missing / broken `ServiceConfiguration` is logged and degraded to `null` /
 * an empty collection instead of raising.
 */
object ServiceLoader {

    /** Key of the default implementation: an empty string means "no key", distinct from business variant labels. */
    const val DEFAULT_KEY = ""

    @Volatile
    private var registry: ProviderRegistry? = null

    /**
     * Loads the configuration eagerly; by default it is loaded lazily on the first lookup.
     *
     * Calling this in `Application.onCreate` makes the (small) cost deterministic and surfaces a
     * missing generated class at startup. It stays optional: the first lookup performs the same
     * thread-safe initialization when this was not called.
     *
     * Safe to call more than once; later calls are no-ops. A missing or invalid configuration is
     * logged and replaced by an empty one, so this method does not throw either.
     */
    @JvmStatic
    fun initialize() {
        obtainRegistry()
    }

    /** Returns whether the configuration has been loaded (possibly as an empty fallback). */
    @JvmStatic
    fun isInitialized(): Boolean = registry != null

    /**
     * Returns the registry, loading it on the first call. Double-checked locking keeps the common
     * path lock-free; the slow path is synchronized and idempotent, so concurrent first lookups
     * construct the configuration once and never throw.
     */
    private fun obtainRegistry(): ProviderRegistry {
        registry?.let { return it }
        synchronized(this) {
            registry?.let { return it }
            val created = try {
                ProviderRegistry(loadConfiguration())
            } catch (t: Throwable) {
                Log.e(TAG, "ServiceConfiguration is unavailable; ServiceLoader will return empty data", t)
                ProviderRegistry(EmptyConfiguration)
            }
            registry = created
            return created
        }
    }

    /** Builds the table from the generated class; [initialize] logs and degrades on any failure. */
    private fun loadConfiguration(): ProviderConfiguration {
        val raw: Any = ServiceConfiguration()
        return raw as? ProviderConfiguration
            ?: throw IllegalStateException("ServiceConfiguration does not implement ProviderConfiguration")
    }

    /** Fallback configuration when the app did not apply the plugin: every query returns empty. */
    private object EmptyConfiguration : ProviderConfiguration {
        override fun services(): Array<Class<*>> = emptyArray()
        override fun keys(): Array<String> = emptyArray()
        override fun providerAt(index: Int): Class<*>? = null
        override fun newProviderAt(index: Int): Any? = null
    }

    private const val TAG = "AndroidSpi"

    /** Test-only: clears the registry and the lazy-initialization state. */
    internal fun resetForTest() {
        synchronized(this) {
            registry = null
        }
    }

    // ── Instance lookup ──────────────────────────────────────────
    /**
     * Returns an instance for `(service, key)`.
     *
     * The lookup probes on demand, so a provider that becomes visible later (for example, after
     * its dynamic module is installed) succeeds on the next call. Availability is a normal
     * state: this method returns null instead of throwing.
     *
     * When several implementations share the key (only possible for the default key), entries
     * are tried in declaration order and the first available one is returned.
     *
     * @param service the service interface
     * @param key the variant label; [DEFAULT_KEY] means the default implementation
     * @return the provider instance, or null when unavailable
     */
    @JvmStatic
    @JvmOverloads
    fun <T : Any> load(service: Class<T>, key: String = DEFAULT_KEY): T? =
        obtainRegistry().load(service, key)

    /**
     * Kotlin convenience form: `load<HomeService>()` / `load<HomeService>("vip")`.
     *
     * @param key the variant label; [DEFAULT_KEY] means the default implementation
     * @return the provider instance, or null when unavailable
     */
    inline fun <reified T : Any> load(key: String = DEFAULT_KEY): T? = load(T::class.java, key)

    // ── Provider handles (aligned with java.util.ServiceLoader.Provider) ──
    /**
     * Returns a handle for `(service, key)` without instantiating the provider.
     *
     * @param service the service interface
     * @param key the variant label; [DEFAULT_KEY] means the default implementation
     * @return the provider handle, or null when unavailable
     */
    @JvmStatic
    @JvmOverloads
    fun <T : Any> providerOf(service: Class<T>, key: String = DEFAULT_KEY): Provider<T>? =
        obtainRegistry().providerOf(service, key)

    /**
     * Returns the first provider of [service], preferring the default key.
     *
     * @param service the service interface
     * @return the first ready provider handle, or null when none is available
     */
    @JvmStatic
    fun <T : Any> findFirst(service: Class<T>): Provider<T>? =
        providerOf(service) ?: stream(service).firstOrNull()

    /**
     * Returns a lazy sequence of every ready provider for [service], aligned with the JDK's
     * `stream()`.
     *
     * Every entry is represented, including several implementations sharing the default key.
     *
     * @param service the service interface
     * @return ready provider handles; empty when none are available
     */
    @JvmStatic
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>> =
        providers(service).asSequence()

    /**
     * Java-friendly equivalent of [stream]: `Sequence` is awkward in Java, so Java callers can
     * take this `List` and call `.stream()` on it.
     *
     * ```java
     * ServiceLoader.providers(HomeService.class).stream().forEach(p -> ...);
     * ```
     *
     * @param service the service interface
     * @return ready provider handles; empty when none are available
     */
    @JvmStatic
    fun <T : Any> providers(service: Class<T>): List<Provider<T>> =
        obtainRegistry().providers(service).orEmpty()

    // ── Class only, no instantiation ─────────────────────────────
    /**
     * Returns the provider class for `(service, key)` without instantiating it or triggering
     * its `<clinit>`.
     *
     * For several default entries the first resolvable class is returned.
     *
     * @param service the service interface
     * @param key the variant label; [DEFAULT_KEY] means the default implementation
     * @return the provider class, or null when unavailable
     */
    @JvmStatic
    @JvmOverloads
    fun <T : Any> loadClass(service: Class<T>, key: String = DEFAULT_KEY): Class<out T>? =
        obtainRegistry().loadClass(service, key)

    // ── All ──────────────────────────────────────────────────────
    /**
     * Returns every available variant of [service] as a `key -> instance` map.
     *
     * The default key may have several implementations; this map keeps the first available one.
     * Use [providers] or [loadAllClasses] to enumerate every entry.
     *
     * @param service the service interface
     * @return available variants keyed by variant label; empty when none are available
     */
    @JvmStatic
    fun <T : Any> loadAll(service: Class<T>): Map<String, T> =
        obtainRegistry().loadAll(service).orEmpty()

    /**
     * Returns every available provider class of [service], without instantiating them.
     *
     * @param service the service interface
     * @return available provider classes; empty when none are available
     */
    @JvmStatic
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>> =
        obtainRegistry().loadAllClasses(service).orEmpty()

    // ── State queries ────────────────────────────────────────────
    /**
     * Returns the current [ProviderState] of `(service, key)`.
     *
     * Several default implementations are aggregated: any READY wins, then any MISSING; DEAD
     * only when every matching entry is dead.
     *
     * @param service the service interface
     * @param key the variant label; [DEFAULT_KEY] means the default implementation
     * @return the current state, or null when the pair is not registered or [initialize] has
     *   not run
     */
    @JvmStatic
    @JvmOverloads
    fun stateOf(service: Class<*>, key: String = DEFAULT_KEY): ProviderState? =
        obtainRegistry().stateOf(service, key)

    /**
     * Returns a state snapshot of the entire table for debugging. Every pair is re-probed, so
     * the result reflects the current process state rather than a cached view.
     *
     * @return `(service name, key, state)` triples; empty before [initialize]
     */
    @JvmStatic
    fun snapshot(): List<Triple<String, String, ProviderState>> =
        obtainRegistry().snapshot().orEmpty()
}
