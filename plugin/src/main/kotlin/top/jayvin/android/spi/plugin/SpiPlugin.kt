package top.jayvin.android.spi.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.SourceDirectories
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * The `top.jayvin.droidspi` plugin.
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
 *
 * Manifests travel through Gradle's variant-aware resolution: each module variant publishes its
 * manifest as a `spi-manifest`-typed artifact on `{variant}RuntimeElements`. The app takes a
 * type-filtered artifact view of its runtime configuration (transitive) and, for the projects
 * listed in its own `dynamicFeatures` DSL, of their runtime elements. This keeps producer task
 * dependencies intact under configuration-cache reuse and isolated projects; a cross-project
 * `Provider`/extension collector would lose them.
 */
class SpiPlugin : Plugin<Project> {

    /** Registers a scan task for every variant and a generate task for application variants. */
    override fun apply(project: Project) {
        val extension = project.extensions.create("spi", SpiExtension::class.java)
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)
        val isApplication = project.plugins.hasPlugin("com.android.application")

        // The DSL is final by the time `onVariants` callbacks run; compute the paths once.
        val dynamicFeatures = lazy { dynamicFeaturePaths(project) }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!extension.enabled) return@onVariants

            val manifest = registerScan(project, variant, extension)

            if (isApplication) {
                registerGenerate(project, variant, manifest, dynamicFeatures.value)
            }
        }
    }

    private fun registerScan(
        project: Project,
        variant: Variant,
        extension: SpiExtension,
    ): Provider<RegularFile> {
        val variantName = variant.name

        val task = project.tasks.register(
            "${variantName.capitalized()}ScanProviders",
            ScanProvidersTask::class.java,
        ) { t ->
            t.group = "spi"
            t.description =
                "Scans this module's $variantName sources, producing a provider manifest"
            // Declared (static) source directories: `main`, the build type and flavor overlays.
            // AGP 8.0-8.3 have no `static` accessor, so the helper falls back to `all` there and
            // generated directories can be scanned as well.
            addStaticSourceDirs(t.sourceDirs, variant.sources.java)
            addStaticSourceDirs(t.sourceDirs, variant.sources.kotlin)
            if (extension.scanMetaInfServices) {
                addStaticSourceDirs(t.sourceDirs, variant.sources.resources)
            }
            t.manifestFile.set(project.spiVariantDir(variantName).map { it.file("services.txt") })
        }
        val manifest = task.flatMap { it.manifestFile }

        // Reachable from any runtime classpath: consumers filter this custom artifact type, so
        // modules without the plugin simply contribute no manifest. Every Android variant has
        // this outgoing configuration; fail loudly if AGP ever stops creating it.
        project.configurations.getByName("${variantName}RuntimeElements")
            .outgoing.artifact(manifest) { artifact ->
                artifact.type = SPI_MANIFEST_ARTIFACT_TYPE
                artifact.builtBy(task)
            }

        return manifest
    }

    private fun registerGenerate(
        project: Project,
        variant: Variant,
        ownManifest: Provider<RegularFile>,
        dynamicFeatures: Set<String>,
    ) {
        val variantName = variant.name

        // Dynamic features are not on the app's runtime classpath. Select their runtime
        // elements with the same attributes as this app variant, then keep only the manifests
        // through the artifact view; a feature without the plugin contributes nothing.
        val dynamicFeatureManifests = project.configurations.create(
            "spi${variantName.capitalized()}DynamicFeatureManifests",
        ) { config ->
            config.isCanBeResolved = true
            config.isCanBeConsumed = false
            config.description = "SPI provider manifests of $variantName dynamic features"
            copyAttributes(variant.runtimeConfiguration.attributes, config.attributes)
        }
        dynamicFeatures.forEach { path ->
            dynamicFeatureManifests.dependencies.add(
                project.dependencies.project(mapOf("path" to path)),
            )
        }

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
            t.manifests.from(
                manifestFiles(variant.runtimeConfiguration),
                manifestFiles(dynamicFeatureManifests),
                ownManifest,
            )
        }

        // Every variant needs its own append. Flavors make several variants share a build type;
        // wiring only the first one would silently leave the others without the generated class.
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toAppend(ScopedArtifact.CLASSES) { t -> t.outputDir }
    }

    /** Files of the custom manifest artifact type carried by [configuration]. */
    private fun manifestFiles(configuration: Configuration) =
        configuration.incoming.artifactView { view ->
            view.attributes.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                SPI_MANIFEST_ARTIFACT_TYPE,
            )
        }.files

    /**
     * Adds the declared source directories of [sources]. AGP 8.4+ exposes `static`; 8.0-8.3
     * only have `all`, which additionally contains directories generated by other plugins.
     */
    private fun addStaticSourceDirs(
        to: ConfigurableFileCollection,
        sources: SourceDirectories.Flat?,
    ) {
        val dirs = sources ?: return
        to.from(if (HAS_STATIC_SOURCES) dirs.static else dirs.all)
    }

    /** Copies every attribute so variant selection follows AGP's own contract for this variant. */
    private fun copyAttributes(from: AttributeContainer, to: AttributeContainer) {
        from.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val attribute = key as Attribute<Any>
            from.getAttribute(attribute)?.let { value -> to.attribute(attribute, value) }
        }
    }

    /** Project paths of the application's dynamic features, declared in its own build script. */
    private fun dynamicFeaturePaths(project: Project): Set<String> =
        project.extensions.findByType(ApplicationExtension::class.java)
            ?.dynamicFeatures
            .orEmpty()
            .filter { it.isNotBlank() }
            .toSet()

    /** Base output directory for one variant's manifest and generated class. */
    private fun Project.spiVariantDir(variantName: String) =
        layout.buildDirectory.dir("generated/spi/$variantName")

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }

    private companion object {
        /** `SourceDirectories.Flat.static` was added in AGP 8.4; older versions only expose `all`. */
        val HAS_STATIC_SOURCES =
            SourceDirectories.Flat::class.java.methods.any { it.name == "static" }
    }
}
