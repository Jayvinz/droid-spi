package top.jayvin.android.spi

/**
 * A ready service provider. Corresponds to `java.util.ServiceLoader.Provider<S>`.
 *
 * In the JDK, `type()` returns the implementation class and `get()` returns an instance; here
 * there is an extra [state], and [get] is nullable — a dynamic module may be uninstalled after
 * a provider has been acquired.
 *
 * Providers obtained from [ServiceLoader.stream] / [ServiceLoader.findFirst] are always
 * [READY][ProviderState.READY].
 *
 * All methods are total: they never throw. Unavailability is reported by [get] returning null
 * and [state] reporting [ProviderState.MISSING] / [ProviderState.DEAD].
 */
interface Provider<S : Any> {

    /**
     * Returns the provider implementation class.
     *
     * Resolves the class only; does not trigger `<clinit>`.
     *
     * @return the provider implementation class
     */
    fun type(): Class<out S>

    /**
     * Returns a new provider instance, triggering the provider's `<clinit>`.
     *
     * @return a provider instance, or null when the provider is no longer available (for
     *   example, its dynamic module was uninstalled)
     */
    fun get(): S?

    /**
     * Returns the provider's current state.
     *
     * @return the current state, usually [ProviderState.READY]
     */
    fun state(): ProviderState
}
