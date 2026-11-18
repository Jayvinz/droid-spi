package top.jayvin.android.spi.plugin

import org.gradle.api.GradleException
import java.io.File

/**
 * Scans standard SPI configuration files under source directories:
 * `META-INF/services/<service FQN>`.
 *
 * <p>The typical location is `src/main/resources/META-INF/services/`. To integrate providers
 * from third-party jars / aars, copy the service configuration declared by those libraries into
 * this module's source directory using standard SPI syntax — we maintain the configuration and
 * the third-party jar supplies the implementation classes.
 *
 * <p>It reads only these text files; it does not scan classes or unzip any dependency artifact,
 * so the overhead is negligible. Each line is one provider FQN, `#` starts a comment, and blank
 * lines are ignored. Standard SPI has no key / factory concept, so entries map to the default
 * key (empty string) and no-arg construction.
 */
internal object ServiceConfigScanner {

    private val CLASS_NAME = Regex("""^[\w.$]+$""")

    fun scan(files: Collection<File>): List<ProviderEntry> {
        val out = LinkedHashSet<ProviderEntry>()

        files.forEach { file ->
            if (!file.isFile) return@forEach

            // Support Windows separators; the file name is the service FQN
            val path = file.path.replace('\\', '/')
            val at = path.indexOf(META_INF_SERVICES)
            if (at < 0) return@forEach
            val service = path.substring(at + META_INF_SERVICES.length)
            if (service.isEmpty() || service.contains('/')) return@forEach

            file.readLines().forEach { raw ->
                val provider = raw.substringBefore('#').trim()
                if (provider.isEmpty()) return@forEach
                if (!CLASS_NAME.matches(provider)) {
                    throw GradleException(
                        "${file.path}: invalid provider name `$provider` (expected a form like com.foo.BarImpl)"
                    )
                }
                out += ProviderEntry(service, provider, "", "")
            }
        }

        return out.toList()
    }
}
