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
 */
interface Provider<S : Any> {

    /** The provider implementation class. Resolves only; does not trigger `<clinit>`. */
    fun type(): Class<out S>

    /** Gets an instance; triggers `<clinit>`. Returns null when the module has been uninstalled, etc. */
    fun get(): S?

    /** Current state, usually [ProviderState.READY]. */
    fun state(): ProviderState
}
