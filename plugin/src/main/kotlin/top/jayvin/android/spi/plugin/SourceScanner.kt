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

/** Marker checked with a cheap substring test before the more expensive annotation scan. */
internal const val SERVICE_PROVIDER_ANNOTATION = "@ServiceProvider"

/**
 * One file's parse result. It depends only on the file text — never on the file name — so it can
 * be cached by content hash: renamed/moved files still hit, and a cache restored from the build
 * cache on another machine stays valid. Diagnostics add the current file name at resolve time.
 */
internal class ParsedFile(
    val packageName: String,
    val explicitImports: Map<String, String>,
    val wildcards: List<String>,
    /** Fully-qualified names of every type declared in the file, for the module-wide index. */
    val declaredTypes: List<String>,
    val entries: List<PendingEntry>,
)

/** One `@ServiceProvider` occurrence whose service expression still needs the module-wide index. */
internal class PendingEntry(
    val serviceExpr: String,
    val key: String,
    val providerSimpleName: String,
    val factory: String,
    /** 1-based line of the annotation, used for diagnostics. */
    val line: Int,
)

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
    private val processed = SourceText.process(raw)
    val clean: String = processed.clean
    val masked: String = processed.masked
}

/**
 * Source scanner. It reads only `.kt` / `.java` under `src/main` and never touches dependency jars.
 *
 * Forms the scanner cannot resolve **fail the build** instead of silently generating a wrong table:
 * - `import a.b.*` where the type is neither explicitly imported nor declared in this module
 *   (the FQN cannot be determined)
 *
 * Nested classes and `typealias` are not supported: the scanner resolves top-level types only.
 */
internal class SourceScanner(private val index: TypeIndex) {

    /** A declaration name and the index just past it; the body is located only when a factory is parsed. */
    private data class ClassDecl(val name: String, val afterName: Int)

    /**
     * Parses one preprocessed document without resolving service names.
     *
     * Declared types are always collected (the module index needs every file, including files
     * without annotations); imports and annotations are only parsed when the cheap marker check
     * passes. The result is path-independent so it can be reused from the parse cache.
     */
    fun discover(doc: SourceDocument): ParsedFile {
        val masked = doc.masked

        // One declaration scan supplies both the header bound and the module index names: the
        // first declaration is where imports stop, and every declaration is a type candidate.
        val declarations = ArrayList<Declaration>()
        DeclarationScanner.forEach(masked) { declarations += it }
        val headerEnd = declarations.firstOrNull()?.start ?: masked.length

        // A substring check is an order of magnitude cheaper than a regex sweep, and most source
        // files contain no annotation at all; skip imports and the annotation scan entirely.
        val hasAnnotation = masked.contains(SERVICE_PROVIDER_ANNOTATION)

        // Package/import parsing is bounded to the file header. Sweeping the whole file with the
        // import regex was the dominant scan cost, because stripped comments leave long whitespace
        // runs that the regex has to walk and backtrack over.
        val header = parseHeader(masked, headerEnd, withImports = hasAnnotation)
        val pkg = header.packageName

        val declared = ArrayList<String>(declarations.size)
        declarations.forEach { declared += qualify(pkg, it.name) }

        if (!hasAnnotation) {
            return ParsedFile(pkg, emptyMap(), emptyList(), declared, emptyList())
        }

        val clean = doc.clean

        val entries = ArrayList<PendingEntry>()
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
            val annotationLine = lineAt(m.range.first)
            val where = "${doc.fileName}:$annotationLine"

            val args = clean.substring(open + 1, close)
            val (serviceExpr, key) = parseArgs(args, where)

            val decl = findClassDecl(masked, close + 1, where)
            val factory = findFactory(masked, decl, where)

            entries += PendingEntry(serviceExpr, key, decl.name, factory, annotationLine)
        }
        return ParsedFile(pkg, header.explicitImports, header.wildcards, declared, entries)
    }

    /** Resolves a [ParsedFile]'s service expressions against this scanner's module-wide index. */
    fun resolve(parsed: ParsedFile, fileName: String): List<ProviderEntry> =
        parsed.entries.map { entry ->
            val where = "$fileName:${entry.line}"
            val service = resolveService(
                entry.serviceExpr,
                parsed.packageName,
                parsed.explicitImports,
                parsed.wildcards,
                where,
            )
            ProviderEntry(
                service,
                qualify(parsed.packageName, entry.providerSimpleName),
                entry.key,
                entry.factory,
            )
        }

    /**
     * Registers every file's declared types, then resolves all entries. Keeping the ordering in
     * one place means the task and the tests cannot drift on the two-phase contract.
     */
    fun resolveAll(files: List<Pair<String, ParsedFile>>): List<ProviderEntry> {
        files.forEach { (_, parsed) -> parsed.declaredTypes.forEach(index::register) }
        return files.flatMap { (fileName, parsed) -> resolve(parsed, fileName) }
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

    private fun resolveService(
        expr: String,
        pkg: String,
        explicitImports: Map<String, String>,
        wildcards: List<String>,
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
                    "and the type is not in this module). Use an explicit import or a fully-qualified name."
            )
        }
        return samePkg
    }

    // ── Class declarations and factory methods ────────────────────
    private fun findClassDecl(masked: String, from: Int, where: String): ClassDecl {
        val declaration = DeclarationScanner.find(masked, from)
            ?: throw GradleException("$where: no class declaration found after @ServiceProvider")
        return ClassDecl(declaration.name, declaration.end)
    }

    private fun findFactory(masked: String, decl: ClassDecl, where: String): String {
        // Most files declare no factory at all; skip brace matching entirely for them.
        if (!masked.contains(PROVIDER_FACTORY)) return ""

        val brace = masked.indexOf('{', decl.afterName)
        if (brace < 0 || brace - (decl.afterName - 1) > MAX_CLASS_HEADER) return ""
        // Another declaration appears before the brace, so this class has no body
        // (Kotlin `class Foo : Bar` without braces).
        if (DeclarationScanner.find(masked, decl.afterName, brace) != null) return ""

        val end = matchPair(masked, brace, '{', '}') + 1

        val idx = indexOfIn(masked, PROVIDER_FACTORY, brace, end)
        if (idx < 0) return ""
        if (indexOfIn(masked, PROVIDER_FACTORY, idx + 1, end) >= 0) {
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

    /** The package and imports declared in one file header. */
    private class Header(
        val packageName: String,
        val explicitImports: Map<String, String>,
        val wildcards: List<String>,
    )

    /**
     * Parses `package` / `import` declarations from the file header only (`[0, headerEnd)`), in a
     * single line walk. The old whole-file `MULTILINE` regex had to walk every line of the file
     * and backtrack over the whitespace left by comment stripping; this is O(header).
     */
    private fun parseHeader(
        masked: String,
        headerEnd: Int,
        withImports: Boolean,
    ): Header {
        var packageName = ""
        val explicit = HashMap<String, String>()
        val wildcards = ArrayList<String>()

        var i = 0
        while (i < headerEnd) {
            var p = i
            while (p < headerEnd && (masked[p] == ' ' || masked[p] == '\t')) p++
            when {
                keywordAt(masked, p, headerEnd, PACKAGE) -> {
                    if (packageName.isEmpty()) {
                        packageName = masked
                            .substring(p + PACKAGE.length, lineEnd(masked, p, headerEnd))
                            .trim().removeSuffix(";").trim()
                    }
                }
                withImports && keywordAt(masked, p, headerEnd, IMPORT) -> {
                    registerImport(
                        masked.substring(p + IMPORT.length, lineEnd(masked, p, headerEnd)),
                        explicit,
                        wildcards,
                    )
                }
            }
            val nl = masked.indexOf('\n', i)
            if (nl < 0) break
            i = nl + 1
        }
        return Header(packageName, explicit, wildcards)
    }

    /** The keyword at [at] must be followed by a non-identifier character or the limit. */
    private fun keywordAt(masked: String, at: Int, limit: Int, keyword: String): Boolean =
        at + keyword.length <= limit &&
            masked.startsWith(keyword, at) &&
            (at + keyword.length == limit || !masked[at + keyword.length].isJavaIdentifierPart())

    /** Registers one `import ...` tail (the text after the keyword) as an explicit or wildcard import. */
    private fun registerImport(
        text: String,
        explicit: HashMap<String, String>,
        wildcards: MutableList<String>,
    ) {
        val rest = text.trim().removeSuffix(";").trim()
        if (rest.isEmpty()) return

        // Java `import static a.b.C.member` imports a member, not a type.
        if (rest.startsWith(STATIC) && rest.getOrNull(STATIC.length)?.isWhitespace() == true) return

        val asMatch = AS_TOKEN.find(rest)
        val fqn = (if (asMatch == null) rest else rest.substring(0, asMatch.range.first)).trim()
        if (fqn.isEmpty()) return

        if (fqn.endsWith(".*")) {
            wildcards += fqn.removeSuffix(".*")
            return
        }

        val alias = asMatch
            ?.let { rest.substring(it.range.last + 1).trim().removeSurrounding("`") }
            ?.takeIf { it.isNotEmpty() }
        explicit[alias ?: fqn.substringAfterLast('.')] = fqn
    }

    private fun lineEnd(masked: String, from: Int, limit: Int): Int {
        val nl = masked.indexOf('\n', from)
        return if (nl < 0 || nl > limit) limit else nl
    }

    companion object {
        private const val MAX_CLASS_HEADER = 300
        private const val MAX_FACTORY_WINDOW = 400
        private const val PROVIDER_FACTORY = "@ProviderFactory"
        private const val PACKAGE = "package"
        private const val IMPORT = "import"
        private const val STATIC = "static"

        private val AS_TOKEN = Regex("""\s+as\s+""")
        private val SERVICE_PROVIDER_RE = Regex("""@ServiceProvider\s*\(""")
        private val STRING_LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
        private val KOTLIN_FUN = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
        private val STATIC_RE = Regex("""\bstatic\b""")
        private val JAVA_FUN = Regex("""([A-Za-z_]\w*)\s*\(""")

        private fun qualify(pkg: String, name: String): String =
            if (pkg.isEmpty()) name else "$pkg.$name"
    }
}
