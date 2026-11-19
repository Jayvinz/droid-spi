package top.jayvin.android.spi.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

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

    /** An application variant waiting for every module's variant entries at `projectsEvaluated`. */
    private class PendingGeneration(
        val task: TaskProvider<GenerateServiceConfigurationTask>,
        val buildType: String?,
        val flavors: Map<String, String>,
    )

    /** Registers a scan task for every variant and a generate task for application variants. */
    override fun apply(project: Project) {
        val extension = project.extensions.create("spi", SpiExtension::class.java)
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)
        val isApplication = project.plugins.hasPlugin("com.android.application")
        val rootExtension = rootExtension(project)
        val pending = mutableListOf<PendingGeneration>()

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!extension.enabled) return@onVariants

            registerScan(project, variant, extension, rootExtension)

            if (isApplication) {
                pending += PendingGeneration(
                    task = registerGenerate(project, variant),
                    buildType = variant.buildType,
                    flavors = variant.productFlavors.toMap(),
                )
            }
        }

        // One hook for every application variant: the `onVariants` callbacks above run during
        // project evaluation, so their entries are complete by the time this fires. Wiring here
        // avoids configuration-time cross-project evaluation order issues, and each manifest
        // Provider carries its producer dependency, so the scan → generate edge is implicit.
        project.gradle.projectsEvaluated {
            val entries = rootExtension.entries
            pending.forEach { generation ->
                val manifests = entries
                    .filter { it.isCompatibleWith(generation.buildType, generation.flavors) }
                    .map { it.manifest }
                generation.task.configure { t -> t.manifests.from(*manifests.toTypedArray()) }
            }
        }
    }

    private fun registerScan(
        project: Project,
        variant: Variant,
        extension: SpiExtension,
        rootExtension: SpiRootExtension,
    ) {
        val variantName = variant.name

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
            t.manifestFile.set(project.spiVariantDir(variantName).map { it.file("services.txt") })
        }

        rootExtension.entries += SpiVariantEntry(
            buildType = variant.buildType,
            flavors = variant.productFlavors,
            manifest = task.flatMap { it.manifestFile },
        )
    }

    private fun registerGenerate(
        project: Project,
        variant: Variant,
    ): TaskProvider<GenerateServiceConfigurationTask> {
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
            t.outputDir.set(project.spiVariantDir(variantName).map { it.dir("classes") })
        }

        // Every variant needs its own append. Flavors make several variants share a build type;
        // wiring only the first one would silently leave the others without the generated class.
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toAppend(ScopedArtifact.CLASSES) { t -> t.outputDir }

        return task
    }

    /** Base output directory for one variant's manifest and generated class. */
    private fun Project.spiVariantDir(variantName: String) =
        layout.buildDirectory.dir("generated/spi/$variantName")

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
