package top.jayvin.android.spi.plugin

/**
 * Gradle DSL extension for the SPI plugin, registered as `spi { ... }` in consumer modules.
 *
 * ```kotlin
 * spi {
 *     scanMetaInfServices = false
 * }
 * ```
 */
open class SpiExtension {
    /** When disabled, this module neither scans nor generates; useful for diagnosing build issues. */
    var enabled: Boolean = true

    /**
     * Whether to read standard SPI configuration `META-INF/services/<service>` under source
     * directories (typically `src/main/resources/META-INF/services/`, but also allowed under
     * `src/main/java` / `src/main/kotlin`). Enabled by default, for integrating third-party
     * providers using standard SPI syntax — the configuration is maintained by this project and
     * the implementation classes are supplied by a third-party jar / aar.
     * Only text manifests are read; dependency artifacts are not unzipped and classes are not scanned.
     */
    var scanMetaInfServices: Boolean = true
}

/** Generated configuration class FQN; must match runtime's compileOnly placeholder and direct `new` target. */
internal const val GENERATED_PACKAGE = "top.jayvin.android.spi.generated"
internal const val GENERATED_CLASS_NAME = "ServiceConfiguration"

/** Standard SPI configuration directory, shared by the scan task's glob and the parser's path matching. */
internal const val META_INF_SERVICES = "META-INF/services/"

/**
 * Custom artifact type used to publish each variant's manifest through Gradle's variant-aware
 * resolution, so producer task dependencies survive configuration-cache reuse and isolated
 * projects.
 */
internal const val SPI_MANIFEST_ARTIFACT_TYPE = "spi-manifest"

/** Java FQN → internal name (slash-separated), shared by the generator and the output file path. */
internal fun String.toInternalName(): String = replace('.', '/')
