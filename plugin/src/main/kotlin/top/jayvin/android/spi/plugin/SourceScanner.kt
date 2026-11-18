package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException

/** 清单里的一行：`service \t provider \t key \t factory`（key 为空串表示默认变体，factory 为空表示走无参构造） */
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
            require(cols.size >= 3) { "清单行格式错误：$line" }
            return ProviderEntry(cols[0], cols[1], cols[2], cols.getOrElse(3) { "" })
        }
    }
}

/**
 * 本模块内所有顶层类型的索引，用于"没有显式 import 也能猜对全限定名"。
 * 只做提示，猜不到就退回同包假设。
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

/** 一个源文件的一次预处理结果：类型索引与注解扫描共用，避免重复读盘与重复词法处理。 */
internal class SourceDocument(val fileName: String, raw: String) {
    val clean: String = SourceText.stripComments(raw)
    val masked: String = SourceText.maskStrings(clean)
}

/**
 * 源码扫描器。只读 `src/main` 下的 .kt / .java，不碰任何依赖 jar。
 *
 * 明确不支持的写法会**直接构建失败**，而不是静默生成错误的配置表：
 * - `import a.b.*` 且该类型未显式 import、也不在本模块内（无法确定全限定名）
 * - 嵌套类、`typealias` 作为 `@ServiceProvider` 的 service
 */
internal class SourceScanner(private val index: TypeIndex) {

    /** [body] 为 null 表示该类没有大括号类体（Kotlin `class Foo : Bar`）。 */
    private data class ClassDecl(val name: String, val body: IntRange?)

    fun scan(doc: SourceDocument): List<ProviderEntry> {
        val clean = doc.clean
        val masked = doc.masked

        val pkg = packageOf(masked)
        val (explicitImports, wildcards) = parseImports(masked)

        val out = ArrayList<ProviderEntry>()
        var cursor = 0
        var line = 1

        // 注解匹配位置递增，游标只前进不回退，整体 O(文件长度)
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

    // ── 参数解析 ──────────────────────────────────────────────────
    private fun parseArgs(args: String, where: String): Pair<String, String> {
        val parts = splitTopLevel(args)
        var serviceExpr: String? = null
        var key = ""

        // 注解改成 Java 后，Kotlin 调用方必须写命名参数 service = X::class；
        // Java 调用方两种写法都可以：@ServiceProvider(X.class) 或 service = X.class。
        parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            when {
                part.startsWith("service") && part.contains('=') ->
                    serviceExpr = part.substringAfter('=').trim()

                part.startsWith("key") && part.contains('=') ->
                    key = stringLiteral(part.substringAfter('='))
                        ?: throw GradleException("$where: key 必须是字符串字面量，实际是 `$part`")

                serviceExpr == null && classLiteralName(part) != null -> serviceExpr = part

                serviceExpr != null && part.startsWith("\"") ->
                    key = stringLiteral(part)
                        ?: throw GradleException("$where: key 必须是字符串字面量，实际是 `$part`")

                else -> throw GradleException("$where: 无法识别的 @ServiceProvider 参数 `$part`")
            }
        }

        if (serviceExpr.isNullOrEmpty()) {
            throw GradleException("$where: @ServiceProvider 缺少 service 参数")
        }
        return serviceExpr!! to key
    }

    private fun stringLiteral(text: String): String? =
        STRING_LITERAL.find(text)?.groupValues?.get(1)

    /** 按顶层逗号切分，忽略括号/尖括号/字符串内部的逗号 */
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

    /** `Home::class` / `Home::class.java` / `Home.class` → `Home`；不是类字面量时返回 null */
    private fun classLiteralName(expr: String): String? {
        val trimmed = expr.trim()
        return when {
            trimmed.endsWith("::class.java") -> trimmed.removeSuffix("::class.java")
            trimmed.endsWith("::class") -> trimmed.removeSuffix("::class")
            trimmed.endsWith(".class") -> trimmed.removeSuffix(".class")
            else -> null
        }
    }

    /** service 参数必须是类字面量；返回去掉字面量后缀的原始名字。 */
    private fun parseServiceType(expr: String, where: String): String {
        val clean = (classLiteralName(expr)
            ?: throw GradleException("$where: service 参数必须写成 `X::class` 或 `X.class`，实际是 `$expr`"))
            .trim()
        if (clean.isEmpty() || clean.contains('<') || clean.contains('*')) {
            throw GradleException("$where: 无法解析 service 类型 `$expr`")
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
        // 已经带包名（或嵌套类型）时按全限定名处理
        if (raw.contains('.')) return raw

        explicitImports[raw]?.let { return it }
        val samePkg = qualify(pkg, raw)
        if (index.contains(samePkg)) return samePkg
        index.unique(raw)?.let { return it }

        if (wildcards.isNotEmpty()) {
            throw GradleException(
                "$where: 无法确定 `$raw` 的全限定名（存在 `import ${wildcards.first()}.*`，" +
                    "且该类型不在本模块内）。请改成显式 import 或写全限定名。file=$fileName"
            )
        }
        return samePkg
    }

    // ── 类声明与工厂方法 ──────────────────────────────────────────
    private fun findClassDecl(masked: String, from: Int, where: String): ClassDecl {
        val m = DECL_RE.find(masked, from)
            ?: throw GradleException("$where: @ServiceProvider 之后没有找到类声明")
        val name = m.groupValues[2]

        val brace = masked.indexOf('{', m.range.last + 1)
        if (brace < 0 || brace - m.range.last > MAX_CLASS_HEADER) return ClassDecl(name, null)
        // 中间又出现新的类声明，说明当前类没有类体（Kotlin `class Foo : Bar` 无大括号）
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
            throw GradleException("$where: 一个类里只能有一个 @ProviderFactory")
        }

        val tail = masked.substring(idx, minOf(idx + MAX_FACTORY_WINDOW, end))

        val kotlinFun = KOTLIN_FUN.find(tail)
        if (kotlinFun != null) {
            val between = tail.substring(0, kotlinFun.range.first)
            if (!between.contains("@JvmStatic")) {
                throw GradleException(
                    "$where: Kotlin 的 @ProviderFactory 必须配合 @JvmStatic（写成 companion object 里的" +
                        " `@ProviderFactory @JvmStatic fun create(): 本类`）"
                )
            }
            return kotlinFun.groupValues[1]
        }

        val staticIdx = STATIC_RE.find(tail)
            ?: throw GradleException("$where: @ProviderFactory 必须标在静态方法上")
        val afterStatic = tail.substring(staticIdx.range.last + 1)
        val javaFun = JAVA_FUN.find(afterStatic)
            ?: throw GradleException("$where: @ProviderFactory 之后没有找到方法名")
        return javaFun.groupValues[1]
    }

    // ── 文本工具 ──────────────────────────────────────────────────
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
        throw GradleException("括号不匹配：offset=$open")
    }

    /** 只在 `[from, to)` 里查找，避免在类体之外命中其他类的注解。 */
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

        /** 把一个源文件里声明的类型全部登记进索引，供全模块范围的名字解析使用 */
        fun collectTypes(masked: String, index: TypeIndex) {
            val pkg = packageOf(masked)
            DECL_RE.findAll(masked).forEach { m -> index.register(qualify(pkg, m.groupValues[2])) }
        }
    }
}
