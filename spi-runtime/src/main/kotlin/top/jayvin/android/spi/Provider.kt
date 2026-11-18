package top.jayvin.android.spi

/**
 * 一个已就绪的 service provider。对应 `java.util.ServiceLoader.Provider<S>`。
 *
 * JDK 里 `type()` 返回实现类、`get()` 返回实例；这里多一个 [state]，
 * 以及 [get] 可空——因为动态模块可能在拿到 provider 后被卸载。
 *
 * 从 [ServiceLoader.stream] / [ServiceLoader.findFirst] 拿到的 provider 一定是 [READY][ProviderState.READY]。
 */
interface Provider<S : Any> {

    /** provider 的实现类。只解析，不触发 `<clinit>`。 */
    fun type(): Class<out S>

    /** 取实例，会触发 `<clinit>`；模块被卸载等情况下返回 null。 */
    fun get(): S?

    /** 当前状态，通常为 [ProviderState.READY]。 */
    fun state(): ProviderState
}
