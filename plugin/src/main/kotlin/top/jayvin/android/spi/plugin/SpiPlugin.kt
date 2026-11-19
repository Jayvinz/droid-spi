package top.jayvin.android.spi.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `top.jayvin.android.spi` plugin.
 *
 * Applied once per Android module:
 * - Every module (library / dynamic-feature / application) registers `{variant}ScanProviders`
 *   to scan that variant's declared sources (main + buildType + flavor overlays);
 * - Only application modules register `{variant}GenerateServiceConfiguration`, which aggregates
 *   the manifests of every compatible module variant and generates the configuration class.
 *
 * Everything is keyed by **variant name**, never by build type alone: two flavors of the same
 * build type compile different sources, so they must also get different tables and different
 * generated classes. Each variant's generate task is appended to its own CLASSES artifact.
 */
class SpiPlugin : Plugin<Project> {

    /** Registers a scan task for every variant and a generate task for application variants. */
    override fun apply(project: Project) {
        val extension = project.extensions.create("spi", SpiExtension::class.java)
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)
        val isApplication = project.plugins.hasPlugin("com.android.application")

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!extension.enabled) return@onVariants

            registerScan(project, variant, extension)

            if (isApplication) {
                registerGenerate(project, variant)
            }
        }
    }

    private fun registerScan(project: Project, variant: Variant, extension: SpiExtension) {
        val variantName = variant.name
        val manifest = project.layout.buildDirectory.file(
            "generated/spi/$variantName/services.txt"
        )

        val task = project.tasks.register(
            "${variantName.capitalized()}ScanProviders",
            ScanProvidersTask::class.java,
        ) { t ->
            t.group = "spi"
            t.description =
                "Scans this module's $variantName sources, producing a provider manifest"
            // Only static (declared) directories: `main`, the build type, and flavor overlays
            // are all part of one variant's static sources. Generated directories belong to
            // other plugins' task graphs and must not be pulled in implicitly.
            variant.sources.java?.let { java -> t.sourceDirs.from(java.static) }
            variant.sources.kotlin?.let { kotlin -> t.sourceDirs.from(kotlin.static) }
            if (extension.scanMetaInfServices) {
                variant.sources.resources?.let { res -> t.sourceDirs.from(res.static) }
            }
            t.manifestFile.set(manifest)
        }

        rootExtension(project).variants
            .getOrPut(project.path) { mutableListOf() }
            .add(
                SpiVariantEntry(
                    variantName = variantName,
                    buildType = variant.buildType,
                    flavors = variant.productFlavors,
                    manifest = task.flatMap { it.manifestFile },
                )
            )
    }

    private fun registerGenerate(project: Project, variant: Variant) {
        val variantName = variant.name
        val task = project.tasks.register(
            "${variantName.capitalized()}GenerateServiceConfiguration",
            GenerateServiceConfigurationTask::class.java,
        ) { t ->
            t.group = "spi"
            t.description =
                "Aggregates compatible modules' $variantName manifests and generates the service configuration class"
            t.packageName.set(GENERATED_PACKAGE)
            t.className.set(GENERATED_CLASS_NAME)
            t.outputDir.set(project.layout.buildDirectory.dir("generated/spi/$variantName/classes"))
        }

        // Every variant needs its own append. Flavors make several variants share a build type;
        // wiring only the first one would silently leave the others without the generated class.
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toAppend(ScopedArtifact.CLASSES) { t -> t.outputDir }

        val buildType = variant.buildType
        val flavors = variant.productFlavors

        // Configuration-time cross-project collection runs into evaluation order; wire inputs
        // only after every project is configured. Providers carry their producer dependency,
        // so the task graph creates the scan → generate edge automatically.
        project.gradle.projectsEvaluated {
            val manifests = rootExtension(project)
                .allVariantEntries()
                .filter { it.isCompatibleWith(buildType, flavors) }
                .map { it.manifest }
            task.configure { t ->
                t.manifests.from(*manifests.toTypedArray())
            }
        }
    }

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
