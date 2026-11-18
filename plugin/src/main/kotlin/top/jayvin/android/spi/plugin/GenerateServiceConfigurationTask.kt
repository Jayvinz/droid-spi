package top.jayvin.android.spi.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Aggregation task for the app module: merges every module's manifest into one table and
 * generates the fixed-FQN provider configuration class.
 *
 * It only reads text manifests and only writes a class directory; it does not read
 * `ScopedArtifact.CLASSES`, so there is no read-while-write cycle.
 */
@CacheableTask
abstract class GenerateServiceConfigurationTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val bySlot = LinkedHashMap<Pair<String, String>, ProviderEntry>()

        manifests.files.filter { it.isFile }.sortedBy { it.path }.forEach { file ->
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val entry = ProviderEntry.decode(line)
                if (entry.service.isEmpty() || entry.provider.isEmpty()) {
                    throw GradleException("Invalid manifest line (${file.name}): $line")
                }
                val prev = bySlot.put(entry.service to entry.key, entry)
                if (prev != null) {
                    throw GradleException(
                        "Duplicate registration: service ${entry.service} key=\"${entry.key}\" is implemented by " +
                            "both ${prev.provider} and ${entry.provider}"
                    )
                }
            }
        }

        val entries = bySlot.values.toList()
        val pkg = packageName.get()
        val simpleName = className.get()

        if (entries.isEmpty()) {
            logger.warn("spi: no @ServiceProvider was found; the provider configuration is empty")
        }

        val bytes = ConfigurationClassGenerator.generate(pkg, simpleName, entries)
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(pkg.toInternalName())
        dir.mkdirs()
        dir.resolve("$simpleName.class").writeBytes(bytes)

        logger.lifecycle("spi: generated ${pkg}.${simpleName} with ${entries.size} provider entries")
    }
}
