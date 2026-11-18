package com.example.spi.plugin

/**
 * 源码文本的两级处理。两个函数**都必须保持字符串长度不变**，
 * 这样同一处偏移量在 clean / masked 两份文本里指向同一个字符。
 *
 * - [stripComments]：注释替换为空格，**字符串原样保留**（key 的取值要从这里读）
 * - [maskStrings]：字符串内容（含引号）替换为空格，保留换行（结构扫描用，避免字符串里的
 *   "class" / "@ServiceProvider" 造成误判）
 */
internal object SourceText {

    fun stripComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        var state = CODE
        var quote = '"'

        while (i < src.length) {
            val c = src[i]
            when (state) {
                CODE -> when {
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                        out[i] = ' '; out[i + 1] = ' '; i += 2; state = LINE_COMMENT
                    }
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                        out[i] = ' '; out[i + 1] = ' '; i += 2; state = BLOCK_COMMENT
                    }
                    c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"' -> {
                        i += 3; state = RAW_STRING
                    }
                    c == '"' -> { i++; state = STRING; quote = '"' }
                    c == '\'' -> { i++; state = STRING; quote = '\'' }
                    else -> i++
                }

                LINE_COMMENT -> {
                    if (c == '\n') state = CODE else out[i] = ' '
                    i++
                }

                BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        out[i] = ' '; out[i + 1] = ' '; i += 2; state = CODE
                    } else {
                        if (c != '\n') out[i] = ' '
                        i++
                    }
                }

                STRING -> {
                    when {
                        c == '\\' -> i += 2
                        c == quote -> { i++; state = CODE }
                        else -> i++
                    }
                }

                RAW_STRING -> {
                    if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                        i += 3; state = CODE
                    } else {
                        i++
                    }
                }
            }
        }
        return String(out)
    }

    fun maskStrings(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '
                    i += 3
                    while (i < src.length) {
                        if (src[i] == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                            out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '
                            i += 3
                            break
                        }
                        if (src[i] != '\n') out[i] = ' '
                        i++
                    }
                }

                c == '"' || c == '\'' -> {
                    val quote = c
                    out[i] = ' '
                    i++
                    while (i < src.length) {
                        val d = src[i]
                        if (d == '\\') {
                            out[i] = ' '
                            if (i + 1 < src.length && src[i + 1] != '\n') out[i + 1] = ' '
                            i += 2
                            continue
                        }
                        if (d == quote) { out[i] = ' '; i++; break }
                        if (d != '\n') out[i] = ' '
                        i++
                    }
                }

                else -> i++
            }
        }
        return String(out)
    }

    private const val CODE = 0
    private const val LINE_COMMENT = 1
    private const val BLOCK_COMMENT = 2
    private const val STRING = 3
    private const val RAW_STRING = 4
}
