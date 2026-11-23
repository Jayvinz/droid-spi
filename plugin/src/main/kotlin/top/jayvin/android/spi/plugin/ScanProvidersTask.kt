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

    /**
     * Per-file parse cache keyed by content hash. On a run where only a few sources changed, the
     * unchanged files are reused from here instead of being lexed again.
     */
    @get:OutputFile
    abstract val parseCacheFile: RegularFileProperty

    /** Scans the configured sources and writes a deterministic manifest. */
    @TaskAction
    fun run() {
        val files = (sourceDirs.files + serviceConfigDirs.files).sortedBy { it.path }

        val sourceFiles = files.filter {
            it.name.endsWith(".kt", ignoreCase = true) ||
                it.name.endsWith(".java", ignoreCase = true)
        }
        val configFiles = files - sourceFiles.toSet()

        // Reuse the previous parse for unchanged content. The cache is keyed by SHA-256 of the
        // file bytes, so renamed files still hit and a cache restored on another machine stays
        // valid. Reading and hashing all sources is far cheaper than lexing them; only changed
        // files reach the scanner.
        val previous = ParseCache.read(parseCacheFile.get().asFile)
        val current = LinkedHashMap<String, ParsedFile>()
        val parsed = ArrayList<Pair<String, ParsedFile>>()
        val index = TypeIndex()
        val scanner = SourceScanner(index)
        var lexed = 0
        var reused = 0
        sourceFiles.forEach { file ->
            val bytes = file.readBytes()
            val hash = ParseCache.sha256(bytes)
            val cached = previous[hash]
            if (cached != null) reused++ else lexed++
            val model = cached ?: scanner.discover(
                SourceDocument(file.name, String(bytes, Charsets.UTF_8)),
            )
            current[hash] = model
            // Discovery never reads the index, so registering as we go is fine: resolution runs
            // only after every file has contributed its declared types.
            model.declaredTypes.forEach(index::register)
            parsed += file.name to model
        }

        val fromSource = parsed.flatMap { (name, model) -> scanner.resolve(model, name) }
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
        ParseCache.write(parseCacheFile.get().asFile, current)

        logger.info(
            "spi: scan finished for {}, {} providers (source {} / SPI config {}; {} files lexed, {} reused) → {}",
            path,
            entries.size,
            fromSource.size,
            fromServiceConfig.size,
            lexed,
            reused,
            out.name,
        )
    }
}
