package top.jayvin.android.spi.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * app 模块的聚合任务：把所有模块的清单合并成一张表，生成固定全类名的 provider configuration class。
 *
 * 只读文本清单、只写 class 目录，不读 `ScopedArtifact.CLASSES`，所以不存在"边读边写"的环。
 */
@CacheableTask
abstract class GenerateServiceConfigurationTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val bySlot = LinkedHashMap<Pair<String, String>, ProviderEntry>()

        manifests.files.filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val entry = ProviderEntry.decode(line)
                if (entry.service.isEmpty() || entry.provider.isEmpty()) {
                    throw GradleException("清单行非法（${file.name}）：$line")
                }
                val prev = bySlot.put(entry.service to entry.key, entry)
                if (prev != null) {
                    throw GradleException(
                        "重复注册：service ${entry.service} 的 key=\"${entry.key}\" 被 " +
                            "${prev.provider} 与 ${entry.provider} 同时实现"
                    )
                }
            }
        }

        val entries = bySlot.values.toList()
        val pkg = packageName.get()
        val simpleName = className.get()

        if (entries.isEmpty()) {
            logger.warn("spi: 没有扫描到任何 @ServiceProvider，provider configuration 为空")
        }

        val bytes = ConfigurationClassGenerator.generate(pkg, simpleName, entries)
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(pkg.toInternalName())
        dir.mkdirs()
        dir.resolve("$simpleName.class").writeBytes(bytes)

        logger.lifecycle("spi: ${pkg}.${simpleName} 生成完成，共 ${entries.size} 条 provider")
    }
}
