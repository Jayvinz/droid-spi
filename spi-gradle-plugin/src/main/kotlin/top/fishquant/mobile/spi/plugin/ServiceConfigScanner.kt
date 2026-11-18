package top.fishquant.mobile.spi.plugin

import org.gradle.api.GradleException
import java.io.File

/**
 * 扫描源码目录下的标准 SPI 配置文件：`META-INF/services/<service 全限定名>`。
 *
 * <p>典型位置是 `src/main/resources/META-INF/services/`。集成第三方 jar / aar 里的 provider 时，
 * 把这些第三方库声明的 service 配置按标准 SPI 语法放到本模块源码目录即可 —— 配置由我们维护，
 * 实现类由第三方 jar 提供。
 *
 * <p>只读这几个文本文件，不扫 class、不解压任何依赖产物，开销可忽略。
 * 文件中的每行是一个 provider 全限定名，`#` 起注释，空行忽略。
 * 标准 SPI 配置没有 key / factory 概念，统一映射为默认 key 和无参构造。
 */
internal object ServiceConfigScanner {

    private const val MARKER = "META-INF/services/"
    private val CLASS_NAME = Regex("""^[\w.$]+$""")

    fun scan(files: Collection<File>): List<ProviderEntry> {
        val out = LinkedHashSet<ProviderEntry>()

        files.forEach { file ->
            if (!file.isFile) return@forEach

            // 兼容 Windows 分隔符；文件名就是 service 全限定名
            val path = file.path.replace('\\', '/')
            val at = path.indexOf(MARKER)
            if (at < 0) return@forEach
            val service = path.substring(at + MARKER.length)
            if (service.isEmpty() || service.contains('/')) return@forEach

            file.readLines().forEach { raw ->
                val provider = raw.substringBefore('#').trim()
                if (provider.isEmpty()) return@forEach
                if (!CLASS_NAME.matches(provider)) {
                    throw GradleException(
                        "${file.path}: 非法 provider 名 `$provider`（应形如 com.foo.BarImpl）"
                    )
                }
                out += ProviderEntry(service, provider, "", "")
            }
        }

        return out.toList()
    }
}
