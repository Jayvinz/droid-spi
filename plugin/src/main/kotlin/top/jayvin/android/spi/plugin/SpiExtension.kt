package top.jayvin.android.spi.plugin

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * Consumers configure this in their module:
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

/** Java FQN → internal name (slash-separated), shared by the generator and the output file path. */
internal fun String.toInternalName(): String = replace('.', '/')

/**
 * Collector mounted on the root project: each module registers its buildType-specific scan
 * task here, and the app module takes them all at `projectsEvaluated`.
 *
 * The key is buildType rather than variant name because the provider configuration only needs
 * to aggregate per buildType: one table each for `debug` / `release`. The cost is that
 * flavor-exclusive services are not collected; multi-flavor projects must align per variant name.
 */
abstract class SpiRootExtension {
    /** buildType -> manifest files produced by each module's scan task */
    val manifests: MutableMap<String, MutableList<Provider<RegularFile>>> = LinkedHashMap()
}

internal fun rootExtension(project: Project): SpiRootExtension {
    val root = project.rootProject
    return root.extensions.findByType(SpiRootExtension::class.java)
        ?: root.extensions.create("spiRoot", SpiRootExtension::class.java)
}
