package top.jayvin.android.spi.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/** Shared TestKit plumbing for the plugin's functional tests. */
abstract class FunctionalTestBase {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    protected fun write(dir: File, path: String, text: String) {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    /** Implementation classpath recorded by java-gradle-plugin for the plugin under test. */
    protected fun pluginClasspath(): List<File> {
        val resource = javaClass.classLoader
            .getResourceAsStream("plugin-under-test-metadata.properties")
            ?: error("plugin-under-test-metadata.properties is not on the test classpath")
        val properties = Properties().apply { resource.use { load(it) } }
        val value = properties.getProperty("implementation-classpath")
            ?: error("implementation-classpath is missing from the plugin-under-test metadata")
        return value.split(File.pathSeparator).map(::File).filter { it.exists() }
    }

    /** Classpath entries as a Groovy `files(...)` argument list. */
    protected fun groovyClasspath(): String =
        pluginClasspath().joinToString(", ") { "'${it.absolutePath.replace('\\', '/')}'" }

    protected fun run(dir: File, vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args, "--stacktrace")
            .build()

    protected fun runAndFail(dir: File, vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args, "--stacktrace")
            .buildAndFail()
}
