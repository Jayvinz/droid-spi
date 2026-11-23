package top.jayvin.android.spi.plugin

/** One `class` / `object` / `interface` declaration found in masked source text. */
internal data class Declaration(
    /** Index of the `class` / `object` / `interface` keyword. */
    val start: Int,
    /** Index just past the declared name. */
    val end: Int,
    val name: String,
)

/**
 * Finds `class` / `object` / `interface` declarations with the same rules as the old
 * `\b(class|object|interface)\s+([A-Za-z_]\w*)` regex, but with a plain character scan.
 *
 * The regex ran over **every** file and was ~185 MB/s on module sources; a character scan is
 * several times faster and allocates no match objects. Matching is deliberately ASCII-only and
 * identical to the regex: word boundary before the keyword, at least one whitespace character
 * after it, then an ASCII identifier.
 */
internal object DeclarationScanner {

    private const val CLASS = "class"
    private const val OBJECT = "object"
    private const val INTERFACE = "interface"

    /** First declaration in `[from, to)`, or null. */
    fun find(masked: String, from: Int = 0, to: Int = masked.length): Declaration? {
        var found: Declaration? = null
        scan(masked, from, to) {
            found = it
            true
        }
        return found
    }

    /** Calls [action] for every declaration in `[0, to)`. */
    fun forEach(masked: String, to: Int = masked.length, action: (Declaration) -> Unit) {
        scan(masked, 0, to) {
            action(it)
            false
        }
    }

    private fun scan(masked: String, from: Int, to: Int, onMatch: (Declaration) -> Boolean) {
        var i = from
        while (i < to) {
            val keywordLength = keywordLengthAt(masked, i, to)
            if (keywordLength == 0) {
                i++
                continue
            }

            // \b before the keyword: `myclass Foo` must not match.
            if (i > 0 && isIdentifierPart(masked[i - 1])) {
                i += keywordLength
                continue
            }

            var p = i + keywordLength
            // \s+ after the keyword.
            if (p >= to || !isRegexWhitespace(masked[p])) {
                i += keywordLength
                continue
            }
            while (p < to && isRegexWhitespace(masked[p])) p++
            if (p >= to || !isIdentifierStart(masked[p])) {
                i += keywordLength
                continue
            }

            var end = p + 1
            while (end < to && isIdentifierPart(masked[end])) end++

            val declaration = Declaration(i, end, masked.substring(p, end))
            if (onMatch(declaration)) return
            i = end
        }
    }

    private fun keywordLengthAt(masked: String, i: Int, to: Int): Int = when {
        masked[i] == 'c' && matches(masked, i, to, CLASS) -> CLASS.length
        masked[i] == 'o' && matches(masked, i, to, OBJECT) -> OBJECT.length
        masked[i] == 'i' && matches(masked, i, to, INTERFACE) -> INTERFACE.length
        else -> 0
    }

    private fun matches(masked: String, i: Int, to: Int, keyword: String): Boolean =
        i + keyword.length <= to && masked.startsWith(keyword, i)

    private fun isIdentifierStart(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '_'

    private fun isIdentifierPart(c: Char): Boolean =
        isIdentifierStart(c) || c in '0'..'9'

    /** Java regex `\s`, i.e. the whitespace set the old pattern accepted. */
    private fun isRegexWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
