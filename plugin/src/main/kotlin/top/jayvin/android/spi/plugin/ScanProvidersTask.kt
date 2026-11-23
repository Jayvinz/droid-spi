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
 * Scan task for a single module variant: reads that variant's declared static sources
 * (`main` plus build type / flavor overlays, wired from `variant.sources`) and produces a
 * `service \t provider \t key \t factory` manifest.
 *
 * The manifest is **deterministic output** (sorted, no timestamps), so downstream generation
 * can stay UP-TO-DATE when sources are unchanged.
 */
@CacheableTask
abstract class ScanProvidersTask : DefaultTask() {

    /**
     * Source directories scanned for Kotlin/Java sources and META-INF/services text files.
     *
     * The plugin declares this as a filtered tree (only Kotlin, Java and service-configuration
     * files), so Gradle fingerprints only the files the task can consume; unrelated files under
     * the same roots do not invalidate the scan.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    /**
     * `META-INF/services/<service>` files reachable from Java resource roots, already filtered by
     * the plugin. Kept separate from [sourceDirs] so that changing an unrelated resource file does
     * not re-run the scan.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serviceConfigDirs: ConfigurableFileCollection

    /** Output manifest listing one `service \t provider \t key \t factory` line per entry. */
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    /** Scans the configured sources and writes a deterministic manifest. */
    @TaskAction
    fun run() {
        val files = (sourceDirs.files + serviceConfigDirs.files).sortedBy { it.path }

        val sourceFiles = files.filter {
            it.name.endsWith(".kt", ignoreCase = true) ||
                it.name.endsWith(".java", ignoreCase = true)
        }
        val configFiles = files - sourceFiles.toSet()

        // Each source file is read and lexed once; the type index and the annotation scan share the
        // cleaned / masked text. Only files that can actually produce entries are retained: the
        // module-wide index is complete by the time they are scanned, and keeping two copies of
        // every source file would otherwise scale the task's peak heap with the whole module.
        val index = TypeIndex()
        val documents = ArrayList<SourceDocument>()
        sourceFiles.forEach { file ->
            val document = SourceDocument(file.name, file.readText())
            SourceScanner.collectTypes(document.masked, index)
            if (document.masked.contains(SERVICE_PROVIDER_ANNOTATION)) documents += document
        }

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
