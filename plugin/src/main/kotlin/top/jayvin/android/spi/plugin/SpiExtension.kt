package top.jayvin.android.spi.plugin

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

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

/** Java FQN → internal name (slash-separated), shared by the generator and the output file path. */
internal fun String.toInternalName(): String = replace('.', '/')

/**
 * One module variant's scan output and the identity used for aggregation.
 *
 * Aggregation happens per **variant**, not per build type: flavors of the same build type compile
 * different sources and therefore contribute different providers.
 */
internal data class SpiVariantEntry(
    val variantName: String,
    val buildType: String?,
    /** Flavor dimensions in dimension order, `(dimension, value)`; empty when the module has no flavors. */
    val flavors: List<Pair<String, String>>,
    val manifest: Provider<RegularFile>,
) {
    /**
     * Whether this module variant feeds the application variant identified by
     * ([buildType], [flavors]).
     *
     * The build type must match. Every flavor dimension declared by **this module** must also
     * exist on the application variant with the same value; a module that declares no flavors
     * (or fewer dimensions) is compatible with every application flavor of that build type.
     * A module dimension missing from the application variant is treated as incompatible —
     * such a dependency would not be variant-matched by AGP either.
     */
    fun isCompatibleWith(
        buildType: String?,
        flavors: List<Pair<String, String>>,
    ): Boolean {
        if (this.buildType != buildType) return false
        val applicationFlavors = flavors.toMap()
        return this.flavors.all { (dimension, value) ->
            applicationFlavors[dimension] == value
        }
    }
}

/**
 * Internal root-project collector: each module registers its per-variant scan tasks here, and
 * the app module takes the compatible subset at `projectsEvaluated`.
 */
internal abstract class SpiRootExtension {
    /** Module path → its variant entries (one per variant the module declares). */
    val variants: MutableMap<String, MutableList<SpiVariantEntry>> = LinkedHashMap()

    /** Every registered variant entry, across modules. */
    internal fun allVariantEntries(): List<SpiVariantEntry> = variants.values.flatten()
}

/** Returns the root project's SPI collector, creating it on first use. */
internal fun rootExtension(project: Project): SpiRootExtension {
    val root = project.rootProject
    return root.extensions.findByType(SpiRootExtension::class.java)
        ?: root.extensions.create("spiRoot", SpiRootExtension::class.java)
}
