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
 * - Every module (library / dynamic-feature / application) registers `xxScanProviders` to scan
 *   its own sources;
 * - Only application modules register `xxGenerateServiceConfiguration`, which aggregates all
 *   manifests and generates the configuration class.
 */
class SpiPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("spi", SpiExtension::class.java)
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!extension.enabled) return@onVariants
            val buildType = variant.buildType ?: "debug"

            registerScan(project, buildType, extension)

            if (project.plugins.hasPlugin("com.android.application")) {
                registerGenerate(project, variant, buildType)
            }
        }
    }

    private fun registerScan(project: Project, buildType: String, extension: SpiExtension) {
        val taskName = "${buildType.capitalized()}ScanProviders"
        if (project.tasks.names.contains(taskName)) return

        val manifest = project.layout.buildDirectory.file(
            "generated/spi/$buildType/services.txt"
        )

        val task = project.tasks.register(taskName, ScanProvidersTask::class.java) { t ->
            t.group = "spi"
            t.description = "Scans this module's annotations and META-INF/services, producing a provider manifest"
            t.sourceDirs.from(
                project.file("src/main/java"),
                project.file("src/main/kotlin"),
            )
            if (extension.scanMetaInfServices) {
                t.sourceDirs.from(project.file("src/main/resources"))
            }
            t.manifestFile.set(manifest)
        }

        rootExtension(project).manifests
            .getOrPut(buildType) { mutableListOf() }
            .add(task.flatMap { it.manifestFile })
    }

    private fun registerGenerate(
        project: Project,
        variant: Variant,
        buildType: String,
    ) {
        val taskName = "${buildType.capitalized()}GenerateServiceConfiguration"
        if (project.tasks.names.contains(taskName)) return

        val task = project.tasks.register(
            taskName,
            GenerateServiceConfigurationTask::class.java,
        ) { t ->
            t.group = "spi"
            t.description = "Aggregates all modules' provider manifests and generates the service configuration class"
            t.packageName.set(GENERATED_PACKAGE)
            t.className.set(GENERATED_CLASS_NAME)
            t.outputDir.set(project.layout.buildDirectory.dir("generated/spi/$buildType/classes"))
        }

        // Write only, never read: it does not touch the ScopedArtifact.CLASSES input, so there is no cycle.
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toAppend(ScopedArtifact.CLASSES) { t -> t.outputDir }

        // Configuration-time cross-project collection runs into evaluation order; wire inputs
        // only after every project is configured. Providers carry their producer dependency,
        // so the task graph creates the scan → generate edge automatically.
        project.gradle.projectsEvaluated {
            val manifests = rootExtension(project).manifests[buildType].orEmpty()
            task.configure { t -> t.manifests.from(*manifests.toTypedArray()) }
        }
    }

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
