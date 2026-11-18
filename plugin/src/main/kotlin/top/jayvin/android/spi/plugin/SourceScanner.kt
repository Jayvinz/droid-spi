package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException

/** One manifest line: `service \t provider \t key \t factory` (an empty key means the default variant; an empty factory means no-arg construction) */
internal data class ProviderEntry(
    val service: String,
    val provider: String,
    val key: String,
    val factory: String,
) {
    fun encode(): String = "$service\t$provider\t$key\t$factory"

    companion object {
        fun decode(line: String): ProviderEntry {
            val cols = line.split('\t')
            require(cols.size >= 3) { "Malformed manifest line: $line" }
            return ProviderEntry(cols[0], cols[1], cols[2], cols.getOrElse(3) { "" })
        }
    }
}

/**
 * An index of all top-level types declared in this module, used to "guess the FQN even without
 * an explicit import". It is only a hint; when it cannot guess, it falls back to the same package.
 */
internal class TypeIndex {
    private val declared = HashSet<String>()
    private val bySimpleName = HashMap<String, MutableSet<String>>()

    fun register(fqn: String) {
        if (!declared.add(fqn)) return
        bySimpleName.getOrPut(fqn.substringAfterLast('.')) { HashSet() }.add(fqn)
    }

    fun contains(fqn: String): Boolean = fqn in declared

    fun unique(simple: String): String? =
        bySimpleName[simple]?.takeIf { it.size == 1 }?.first()
}

/** One preprocessing result per source file, shared by the type index and the annotation scan to avoid re-reading and re-lexing. */
internal class SourceDocument(val fileName: String, raw: String) {
    val clean: String = SourceText.stripComments(raw)
    val masked: String = SourceText.maskStrings(clean)
}

/**
 * Source scanner. It reads only `.kt` / `.java` under `src/main` and never touches dependency jars.
 *
 * Explicitly unsupported forms **fail the build** instead of silently generating a wrong table:
 * - `import a.b.*` where the type is neither explicitly imported nor declared in this module
 *   (the FQN cannot be determined)
 * - nested classes or `typealias` as the `@ServiceProvider` service
 */
internal class SourceScanner(private val index: TypeIndex) {

    /** A null [body] means the class has no brace-delimited body (Kotlin `class Foo : Bar`). */
    private data class ClassDecl(val name: String, val body: IntRange?)

    fun scan(doc: SourceDocument): List<ProviderEntry> {
        val clean = doc.clean
        val masked = doc.masked

        val pkg = packageOf(masked)
        val (explicitImports, wildcards) = parseImports(masked)

        val out = ArrayList<ProviderEntry>()
        var cursor = 0
        var line = 1

        // Annotation match positions increase monotonically, and the cursor only moves forward,
        // so this is O(file length) overall.
        fun lineAt(offset: Int): Int {
            while (cursor < offset) {
                if (masked[cursor] == '\n') line++
                cursor++
            }
            return line
        }

        SERVICE_PROVIDER_RE.findAll(masked).forEach { m ->
            val open = m.range.last
            val close = matchPair(masked, open, '(', ')')
            val where = "${doc.fileName}:${lineAt(m.range.first)}"

            val args = clean.substring(open + 1, close)
            val (serviceExpr, key) = parseArgs(args, where)
            val service = resolve(serviceExpr, pkg, explicitImports, wildcards, doc.fileName, where)

            val decl = findClassDecl(masked, close + 1, where)
            val provider = qualify(pkg, decl.name)
            val factory = findFactory(masked, decl, where)

            out += ProviderEntry(service, provider, key, factory)
        }
        return out
    }

    // ── Argument parsing ──────────────────────────────────────────
    private fun parseArgs(args: String, where: String): Pair<String, String> {
        val parts = splitTopLevel(args)
        var serviceExpr: String? = null
        var key = ""

        // Since the annotation is Java, Kotlin callers must use the named argument service = X::class;
        // Java callers accept both forms: @ServiceProvider(X.class) or service = X.class.
        parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            when {
                part.startsWith("service") && part.contains('=') ->
                    serviceExpr = part.substringAfter('=').trim()

                part.startsWith("key") && part.contains('=') ->
                    key = stringLiteral(part.substringAfter('='))
                        ?: throw GradleException("$where: key must be a string literal, got `$part`")

                serviceExpr == null && classLiteralName(part) != null -> serviceExpr = part

                serviceExpr != null && part.startsWith("\"") ->
                    key = stringLiteral(part)
                        ?: throw GradleException("$where: key must be a string literal, got `$part`")

                else -> throw GradleException("$where: unrecognized @ServiceProvider argument `$part`")
            }
        }

        if (serviceExpr.isNullOrEmpty()) {
            throw GradleException("$where: @ServiceProvider is missing the service argument")
        }
        return serviceExpr!! to key
    }

    private fun stringLiteral(text: String): String? =
        STRING_LITERAL.find(text)?.groupValues?.get(1)

    /** Split on top-level commas, ignoring commas inside brackets, angle brackets or strings. */
    private fun splitTopLevel(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                    if (c == '"') inString = false
                }
                c == '"' -> { inString = true; sb.append(c) }
                c == '(' || c == '<' || c == '[' || c == '{' -> { depth++; sb.append(c) }
                c == ')' || c == '>' || c == ']' || c == '}' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    /** `Home::class` / `Home::class.java` / `Home.class` → `Home`; returns null when it is not a class literal. */
    private fun classLiteralName(expr: String): String? {
        val trimmed = expr.trim()
        return when {
            trimmed.endsWith("::class.java") -> trimmed.removeSuffix("::class.java")
            trimmed.endsWith("::class") -> trimmed.removeSuffix("::class")
            trimmed.endsWith(".class") -> trimmed.removeSuffix(".class")
            else -> null
        }
    }

    /** The service argument must be a class literal; returns the raw name with the literal suffix removed. */
    private fun parseServiceType(expr: String, where: String): String {
        val clean = (classLiteralName(expr)
            ?: throw GradleException("$where: the service argument must be `X::class` or `X.class`, got `$expr`"))
            .trim()
        if (clean.isEmpty() || clean.contains('<') || clean.contains('*')) {
            throw GradleException("$where: cannot parse service type `$expr`")
        }
        return clean
    }

    private fun resolve(
        expr: String,
        pkg: String,
        explicitImports: Map<String, String>,
        wildcards: List<String>,
        fileName: String,
        where: String,
    ): String {
        val raw = parseServiceType(expr, where)
        // Already qualified (or a nested type): treat it as a fully-qualified name
        if (raw.contains('.')) return raw

        explicitImports[raw]?.let { return it }
        val samePkg = qualify(pkg, raw)
        if (index.contains(samePkg)) return samePkg
        index.unique(raw)?.let { return it }

        if (wildcards.isNotEmpty()) {
            throw GradleException(
                "$where: cannot determine the FQN of `$raw` (there is `import ${wildcards.first()}.*` " +
                    "and the type is not in this module). Use an explicit import or a fully-qualified name. file=$fileName"
            )
        }
        return samePkg
    }

    // ── Class declarations and factory methods ────────────────────
    private fun findClassDecl(masked: String, from: Int, where: String): ClassDecl {
        val m = DECL_RE.find(masked, from)
            ?: throw GradleException("$where: no class declaration found after @ServiceProvider")
        val name = m.groupValues[2]

        val brace = masked.indexOf('{', m.range.last + 1)
        if (brace < 0 || brace - m.range.last > MAX_CLASS_HEADER) return ClassDecl(name, null)
        // Another class declaration appears in between, so the current class has no body
        // (Kotlin `class Foo : Bar` without braces).
        if (DECL_RE.containsMatchIn(masked.substring(m.range.last + 1, brace))) {
            return ClassDecl(name, null)
        }
        return ClassDecl(name, brace..matchPair(masked, brace, '{', '}'))
    }

    private fun findFactory(masked: String, decl: ClassDecl, where: String): String {
        val body = decl.body ?: return ""
        val end = body.last + 1

        val idx = indexOfIn(masked, "@ProviderFactory", body.first, end)
        if (idx < 0) return ""
        if (indexOfIn(masked, "@ProviderFactory", idx + 1, end) >= 0) {
            throw GradleException("$where: a class may contain only one @ProviderFactory")
        }

        val tail = masked.substring(idx, minOf(idx + MAX_FACTORY_WINDOW, end))

        val kotlinFun = KOTLIN_FUN.find(tail)
        if (kotlinFun != null) {
            val between = tail.substring(0, kotlinFun.range.first)
            if (!between.contains("@JvmStatic")) {
                throw GradleException(
                    "$where: Kotlin's @ProviderFactory must be paired with @JvmStatic (write it as " +
                        "`@ProviderFactory @JvmStatic fun create(): ThisClass` inside a companion object)"
                )
            }
            return kotlinFun.groupValues[1]
        }

        val staticIdx = STATIC_RE.find(tail)
            ?: throw GradleException("$where: @ProviderFactory must be placed on a static method")
        val afterStatic = tail.substring(staticIdx.range.last + 1)
        val javaFun = JAVA_FUN.find(afterStatic)
            ?: throw GradleException("$where: no method name found after @ProviderFactory")
        return javaFun.groupValues[1]
    }

    // ── Text utilities ────────────────────────────────────────────
    private fun matchPair(masked: String, open: Int, l: Char, r: Char): Int {
        var depth = 0
        var i = open
        while (i < masked.length) {
            when (masked[i]) {
                l -> depth++
                r -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw GradleException("Unmatched bracket: offset=$open")
    }

    /** Search only within `[from, to)` so annotations from other classes cannot be matched. */
    private fun indexOfIn(text: String, needle: String, from: Int, to: Int): Int {
        if (from >= to) return -1
        val i = text.indexOf(needle, from)
        return if (i in from until to) i else -1
    }

    private fun parseImports(masked: String): Pair<Map<String, String>, List<String>> {
        val explicit = HashMap<String, String>()
        val wildcards = ArrayList<String>()
        IMPORT_RE.findAll(masked).forEach { m ->
            val fqn = m.groupValues[1]
            val alias = m.groupValues[2]
            if (fqn.endsWith(".*")) {
                wildcards += fqn.removeSuffix(".*")
            } else {
                explicit[alias.ifEmpty { fqn.substringAfterLast('.') }] = fqn
            }
        }
        return explicit to wildcards
    }

    companion object {
        private const val MAX_CLASS_HEADER = 300
        private const val MAX_FACTORY_WINDOW = 400

        private val PACKAGE_RE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        private val IMPORT_RE = Regex("""^\s*import\s+([\w.*]+)(?:\s+as\s+(\w+))?""", RegexOption.MULTILINE)
        private val SERVICE_PROVIDER_RE = Regex("""@ServiceProvider\s*\(""")
        private val STRING_LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
        private val DECL_RE = Regex("""\b(class|object|interface)\s+([A-Za-z_]\w*)""")
        private val KOTLIN_FUN = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
        private val STATIC_RE = Regex("""\bstatic\b""")
        private val JAVA_FUN = Regex("""([A-Za-z_]\w*)\s*\(""")

        private fun packageOf(masked: String): String =
            PACKAGE_RE.find(masked)?.groupValues?.get(1).orEmpty()

        private fun qualify(pkg: String, name: String): String =
            if (pkg.isEmpty()) name else "$pkg.$name"

        /** Registers every type declared in one source file into the index, for module-wide name resolution. */
        fun collectTypes(masked: String, index: TypeIndex) {
            val pkg = packageOf(masked)
            DECL_RE.findAll(masked).forEach { m -> index.register(qualify(pkg, m.groupValues[2])) }
        }
    }
}
