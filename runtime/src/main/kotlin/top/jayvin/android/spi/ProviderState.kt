package top.jayvin.android.spi

/**
 * Availability of a provider within the current process.
 *
 * The JDK SPI has no such concept — its classpath does not change after startup. AAB dynamic
 * delivery makes providers become visible only during runtime, so the intermediate "not
 * installed yet" state needs to be modeled.
 */
enum class ProviderState {
    /** The provider class is currently invisible (dynamic module not installed); retryable. */
    MISSING,

    /** The provider class is resolvable. */
    READY,

    /** The provider's `<clinit>` failed; the JVM has marked it erroneous, irreversible in this process. */
    DEAD,
}
