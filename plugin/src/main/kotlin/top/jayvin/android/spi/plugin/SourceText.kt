package top.jayvin.android.spi.plugin

/**
 * Source-text preprocessing. Both outputs **must preserve string length**, so the same offset
 * points at the same character in the clean / masked texts.
 *
 * - [ProcessedSource.clean]: comments are replaced with spaces, **strings are kept intact**
 *   (annotation `key` literals are read from here)
 * - [ProcessedSource.masked]: comments and string contents (including quotes) are replaced with
 *   spaces while preserving newlines (structural scanning, so `class` / `@ServiceProvider` inside
 *   strings or comments cannot cause false hits)
 *
 * Both are produced by a single state machine in one pass: the old implementation scanned the
 * whole file twice and built an intermediate `clean` string that the masking pass then re-read.
 */
internal object SourceText {

    /** One preprocessed file: comment-stripped text and the structurally masked text. */
    class ProcessedSource(val clean: String, val masked: String)

    fun process(src: String): ProcessedSource {
        val clean = src.toCharArray()
        val masked = src.toCharArray()

        var i = 0
        var state = CODE
        var quote = '"'

        while (i < src.length) {
            val c = src[i]
            when (state) {
                CODE -> when {
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                        clean[i] = ' '; clean[i + 1] = ' '
                        masked[i] = ' '; masked[i + 1] = ' '
                        i += 2
                        state = LINE_COMMENT
                    }
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                        clean[i] = ' '; clean[i + 1] = ' '
                        masked[i] = ' '; masked[i + 1] = ' '
                        i += 2
                        state = BLOCK_COMMENT
                    }
                    c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"' -> {
                        masked[i] = ' '; masked[i + 1] = ' '; masked[i + 2] = ' '
                        i += 3
                        state = RAW_STRING
                    }
                    c == '"' -> {
                        masked[i] = ' '
                        i++
                        state = STRING
                        quote = '"'
                    }
                    c == '\'' -> {
                        masked[i] = ' '
                        i++
                        state = STRING
                        quote = '\''
                    }
                    else -> i++
                }

                LINE_COMMENT -> {
                    if (c == '\n') {
                        state = CODE
                    } else {
                        clean[i] = ' '; masked[i] = ' '
                    }
                    i++
                }

                BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        clean[i] = ' '; clean[i + 1] = ' '
                        masked[i] = ' '; masked[i + 1] = ' '
                        i += 2
                        state = CODE
                    } else {
                        if (c != '\n') {
                            clean[i] = ' '; masked[i] = ' '
                        }
                        i++
                    }
                }

                STRING -> {
                    when {
                        c == '\\' -> {
                            // Keep the escape in `clean`; blank it in `masked`, except a newline
                            // (line continuation) which stays so offsets and line numbers hold.
                            masked[i] = ' '
                            if (i + 1 < src.length && src[i + 1] != '\n') masked[i + 1] = ' '
                            i += 2
                        }
                        c == quote -> {
                            masked[i] = ' '
                            i++
                            state = CODE
                        }
                        else -> {
                            if (c != '\n') masked[i] = ' '
                            i++
                        }
                    }
                }

                RAW_STRING -> {
                    if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                        masked[i] = ' '; masked[i + 1] = ' '; masked[i + 2] = ' '
                        i += 3
                        state = CODE
                    } else {
                        if (c != '\n') masked[i] = ' '
                        i++
                    }
                }
            }
        }
        return ProcessedSource(String(clean), String(masked))
    }

    private const val CODE = 0
    private const val LINE_COMMENT = 1
    private const val BLOCK_COMMENT = 2
    private const val STRING = 3
    private const val RAW_STRING = 4
}
