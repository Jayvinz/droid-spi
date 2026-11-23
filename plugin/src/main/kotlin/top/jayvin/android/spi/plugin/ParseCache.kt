package top.jayvin.android.spi.plugin

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Per-file parse cache for [ScanProvidersTask].
 *
 * Records are keyed by the SHA-256 of the file bytes rather than by path, so a renamed or moved
 * file still hits and a cache restored from Gradle's build cache on another machine stays valid.
 * [ParsedFile] carries no file name for the same reason; diagnostics are reconstructed when the
 * entries are resolved.
 *
 * Any malformed record makes the whole cache unreadable: the task then re-parses every file,
 * which is always correct and only costs build time.
 *
 * **Bump `HEADER` whenever [SourceScanner.discover] changes what it records or how it resolves
 * it.** The cache file survives plugin upgrades inside `build/`, and a content hash alone cannot
 * tell that the parser implementation changed.
 */
internal object ParseCache {

    private const val HEADER = "droid-spi-parse-cache-v2"
    private const val COLUMNS = 7
    private const val RECORD_END = "#"

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val hex = "0123456789abcdef".toCharArray()

    /** Reads the cache, or returns an empty map when it is missing, stale or malformed. */
    fun read(file: File): Map<String, ParsedFile> {
        return try {
            if (!file.isFile) return emptyMap()
            val lines = file.readLines()
            if (lines.firstOrNull() != HEADER) return emptyMap()

            val out = LinkedHashMap<String, ParsedFile>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val cols = line.split('\t')
                require(cols.size == COLUMNS && cols[COLUMNS - 1] == RECORD_END) {
                    "Malformed parse-cache record: $line"
                }
                out[cols[0]] = ParsedFile(
                    packageName = decode(cols[1]),
                    explicitImports = decodePairs(cols[2]) { first, second ->
                        decode(first) to decode(second)
                    }.toMap(),
                    wildcards = decodeList(cols[3]).map(::decode),
                    declaredTypes = decodeList(cols[4]).map(::decode),
                    entries = decodeList(cols[5]).map { record ->
                        val parts = record.split(':')
                        require(parts.size == 5) { "Malformed parse-cache entry: $record" }
                        PendingEntry(
                            serviceExpr = decode(parts[0]),
                            key = decode(parts[1]),
                            providerSimpleName = decode(parts[2]),
                            factory = decode(parts[3]),
                            line = parts[4].toInt(),
                        )
                    },
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Writes [files] deterministically (sorted by content hash). */
    fun write(file: File, files: Map<String, ParsedFile>) {
        val text = buildString {
            append(HEADER).append('\n')
            files.entries.sortedBy { it.key }.forEach { (hash, parsed) ->
                append(hash).append('\t')
                append(encode(parsed.packageName)).append('\t')
                append(parsed.explicitImports.entries.joinToString(",") {
                    "${encode(it.key)}:${encode(it.value)}"
                }).append('\t')
                append(parsed.wildcards.joinToString(",") { encode(it) }).append('\t')
                append(parsed.declaredTypes.joinToString(",") { encode(it) }).append('\t')
                append(parsed.entries.joinToString(",") {
                    listOf(
                        encode(it.serviceExpr),
                        encode(it.key),
                        encode(it.providerSimpleName),
                        encode(it.factory),
                        it.line.toString(),
                    ).joinToString(":")
                }).append('\t')
                append(RECORD_END).append('\n')
            }
        }
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    /** Lower-case hex SHA-256 of [bytes], used as the cache key. */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach {
                append(hex[(it.toInt() shr 4) and 0xF])
                append(hex[it.toInt() and 0xF])
            }
        }
    }

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(decoder.decode(value), Charsets.UTF_8)

    private fun decodeList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(',')

    private inline fun <T> decodePairs(value: String, transform: (String, String) -> T): List<T> =
        decodeList(value).map { pair ->
            val parts = pair.split(':')
            require(parts.size == 2) { "Malformed parse-cache pair: $pair" }
            transform(parts[0], parts[1])
        }
}
