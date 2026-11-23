package top.jayvin.android.spi.plugin

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

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
 * **Bump `HEADER` whenever [SourceText.process], [DeclarationScanner], [SourceScanner.discover]
 * or the resolve-time schema changes.** The cache file survives plugin upgrades inside `build/`,
 * and a content hash alone cannot tell that the parser implementation changed.
 */
internal object ParseCache {

    private const val HEADER = "droid-spi-parse-cache-v3"
    private const val COLUMNS = 6

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private val hexFormat = HexFormat.of()

    /** Reads the cache, or returns an empty map when it is missing, stale or malformed. */
    fun read(file: File): Map<String, ParsedFile> {
        if (!file.isFile) return emptyMap()
        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                if (reader.readLine() != HEADER) return emptyMap()

                val out = LinkedHashMap<String, ParsedFile>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    val cols = line.split('\t')
                    require(cols.size == COLUMNS) { "Malformed parse-cache record: $line" }
                    out[cols[0]] = ParsedFile(
                        packageName = decode(cols[1]),
                        explicitImports = decodeList(cols[2]).associate { pair ->
                            val parts = pair.split(':')
                            require(parts.size == 2) { "Malformed parse-cache pair: $pair" }
                            decode(parts[0]) to decode(parts[1])
                        },
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
            }
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
                }).append('\n')
            }
        }
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    /** Lower-case hex SHA-256 of [bytes], used as the cache key. */
    fun sha256(bytes: ByteArray): String =
        sha256(MessageDigest.getInstance("SHA-256"), bytes)

    /** Same, reusing [digest] across files; `digest(bytes)` resets it. */
    fun sha256(digest: MessageDigest, bytes: ByteArray): String =
        hexFormat.formatHex(digest.digest(bytes))

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(decoder.decode(value), Charsets.UTF_8)

    private fun decodeList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(',')
}
