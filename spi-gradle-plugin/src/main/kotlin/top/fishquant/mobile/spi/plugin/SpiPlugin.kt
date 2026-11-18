package top.fishquant.mobile.spi.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `top.fishquant.mobile.spi` 插件。
 *
 * 每个 Android 模块 apply 一次：
 * - 所有模块（library / dynamic-feature / application）注册 `xxScanProviders` 扫描本模块源码；
 * - 只有 application 模块注册 `xxGenerateServiceConfiguration`，聚合全部清单并生成配置 class。
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
                registerGenerate(project, variant, buildType, extension)
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
            t.description = "扫描本模块源码注解与 META-INF/services，产出 provider 清单"
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
        extension: SpiExtension,
    ) {
        val taskName = "${buildType.capitalized()}GenerateServiceConfiguration"
        if (project.tasks.names.contains(taskName)) return

        val task = project.tasks.register(
            taskName,
            GenerateServiceConfigurationTask::class.java,
        ) { t ->
            t.group = "spi"
            t.description = "聚合所有模块的 provider 清单，生成 service configuration class"
            t.packageName.set(extension.generatedPackage)
            t.className.set(extension.generatedClassName)
            t.outputDir.set(project.layout.buildDirectory.dir("generated/spi/$buildType/classes"))
        }

        // 只写不读：不碰 ScopedArtifact.CLASSES 的输入，因此不成环
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(task)
            .toAppend(ScopedArtifact.CLASSES) { t -> t.outputDir }

        // 配置期跨工程收集会踩求值顺序，推迟到所有工程配置完成后再接输入。
        // Provider 携带 producer 依赖，任务图里会自动生成 scan → generate 的边。
        project.gradle.projectsEvaluated {
            val manifests = rootExtension(project).manifests[buildType].orEmpty()
            task.configure { t -> t.manifests.from(*manifests.toTypedArray()) }
        }
    }

    private fun String.capitalized(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
