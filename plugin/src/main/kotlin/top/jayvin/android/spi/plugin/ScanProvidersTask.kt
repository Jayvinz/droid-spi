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
 * Scan task for a single module: reads only this module's `src/main` sources and produces a
 * `service \t provider \t key \t factory` manifest.
 *
 * The manifest is **deterministic output** (sorted, no timestamps), so downstream generation
 * can stay UP-TO-DATE when sources are unchanged.
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

        // Each source file is read once; the index and the annotation scan share the cleaned / masked text.
        val documents = sourceFiles.map { SourceDocument(it.name, it.readText()) }
        val index = TypeIndex()
        documents.forEach { SourceScanner.collectTypes(it.masked, index) }

        val scanner = SourceScanner(index)
        val fromSource = documents.flatMap { scanner.scan(it) }
        val fromServiceConfig = ServiceConfigScanner.scan(configFiles)

        // The same provider may be declared both by an annotation and by a third-party jar's
        // META-INF/services; deduplicate by identical line here. Different providers sharing the
        // same (service, key) still fail in the aggregation task.
        val entries = (fromSource + fromServiceConfig)
            .distinct()
            .sortedBy { it.encode() }

        val text = entries.joinToString(separator = "\n") { it.encode() } +
            (if (entries.isEmpty()) "" else "\n")

        val out = manifestFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)
        logger.info(
            "spi: scan finished for {}, {} providers (source {} / SPI config {}) → {}",
            path,
            entries.size,
            fromSource.size,
            fromServiceConfig.size,
            out.name,
        )
    }
}
