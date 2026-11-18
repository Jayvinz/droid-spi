package top.jayvin.android.spi.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 单个模块的扫描任务：只读本模块 `src/main` 下的源码，产出 `service \t provider \t key \t factory` 清单。
 *
 * 清单是**确定性输出**（排序、无时间戳），这样下游的生成任务在源码没变时能命中 UP-TO-DATE。
 */
@CacheableTask
abstract class ScanProvidersTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun run() {
        val files = sourceDirs.asFileTree
            .matching { it.include("**/*.kt", "**/*.java", "**/$META_INF_SERVICES**") }
            .files
            .sortedBy { it.path }

        val sourceFiles = files.filter {
            it.name.endsWith(".kt", ignoreCase = true) ||
                it.name.endsWith(".java", ignoreCase = true)
        }
        val configFiles = files - sourceFiles.toSet()

        // 每个源文件只读一次，去注释 / 掩码的结果由索引与注解扫描共用。
        val documents = sourceFiles.map { SourceDocument(it.name, it.readText()) }
        val index = TypeIndex()
        documents.forEach { SourceScanner.collectTypes(it.masked, index) }

        val scanner = SourceScanner(index)
        val fromSource = documents.flatMap { scanner.scan(it) }
        val fromServiceConfig = ServiceConfigScanner.scan(configFiles)

        // 同一 provider 可能既被注解声明、又被第三方 jar 的 META-INF/services 声明，
        // 这里按整行去重；不同 provider 共用同一 (service, key) 仍由聚合任务报错。
        val entries = (fromSource + fromServiceConfig)
            .distinct()
            .sortedBy { it.encode() }

        val text = entries.joinToString(separator = "\n") { it.encode() } +
            (if (entries.isEmpty()) "" else "\n")

        val out = manifestFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)
        logger.info(
            "spi: {} 扫描完成，{} 条 provider（源码 {} / SPI 配置 {}）→ {}",
            path,
            entries.size,
            fromSource.size,
            fromServiceConfig.size,
            out.name,
        )
    }
}
