package top.jayvin.android.spi.plugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task-level functional tests: real Gradle builds, TestKit-injected plugin classpath, no AGP.
 *
 * They cover the scan/generate pipeline end to end, UP-TO-DATE and incremental behavior, and the
 * build failures. AGP's variant wiring, artifact transport and the generated class inside an APK
 * are covered by [AgpFunctionalTest] and the `compat/` fixtures.
 */
class ScanGenerateFunctionalTest : FunctionalTestBase() {

    private fun newProject(buildScript: String): File {
        val dir = temporaryFolder.newFolder("project")
        File(dir, "settings.gradle").writeText("rootProject.name = 'spi-functional'\n")
        File(dir, "build.gradle").writeText(
            "buildscript { dependencies { classpath files(${groovyClasspath()}) } }\n\n$buildScript",
        )
        return dir
    }

    private fun javaInterface(name: String): String = """
        package com.example;

        public interface $name {}
    """.trimIndent() + "\n"

    private fun javaProvider(service: String, impl: String): String = """
        package com.example;

        import top.jayvin.android.spi.ServiceProvider;

        @ServiceProvider(service = $service.class)
        public class $impl implements $service {}
    """.trimIndent() + "\n"

    @Test
    fun `scan writes a sorted manifest, is up to date, and reuses parsed files`() {
        val dir = newProject(SCAN_SCRIPT)
        write(dir, "src/com/example/AService.java", javaInterface("AService"))
        write(dir, "src/com/example/AImpl.java", javaProvider("AService", "AImpl"))
        write(dir, "src/com/example/BService.java", javaInterface("BService"))
        write(dir, "src/com/example/BImpl.java", javaProvider("BService", "BImpl"))
        write(dir, "resources/META-INF/services/com.example.CService", "com.example.CImpl\n")

        val expected = listOf(
            "com.example.AService\tcom.example.AImpl\t\t",
            "com.example.BService\tcom.example.BImpl\t\t",
            "com.example.CService\tcom.example.CImpl\t\t",
        )
        val manifestFile = File(dir, "build/spi/services.txt")

        val first = run(dir, "scan", "--info")
        assertEquals(TaskOutcome.SUCCESS, first.task(":scan")?.outcome)
        assertTrue(first.output.contains("4 files lexed, 0 reused"))
        assertEquals(expected, manifestFile.readText().lines().filter { it.isNotEmpty() })

        val second = run(dir, "scan")
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":scan")?.outcome)

        // One changed source is re-lexed; the rest come from the content-hash parse cache.
        write(dir, "src/com/example/AImpl.java", javaProvider("AService", "AImpl") + "// touched\n")
        val changed = run(dir, "scan", "--info")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":scan")?.outcome)
        assertTrue(changed.output.contains("1 files lexed, 3 reused"))
        assertEquals(expected, manifestFile.readText().lines().filter { it.isNotEmpty() })

        // A services-config change runs the task but reuses every source parse.
        write(dir, "resources/META-INF/services/com.example.CService", "# changed\ncom.example.CImpl\n")
        val configOnly = run(dir, "scan", "--info")
        assertEquals(TaskOutcome.SUCCESS, configOnly.task(":scan")?.outcome)
        assertTrue(configOnly.output.contains("0 files lexed, 4 reused"))
        assertEquals(expected, manifestFile.readText().lines().filter { it.isNotEmpty() })
    }

    @Test
    fun `generate merges module manifests into the configuration class`() {
        val dir = newProject(GENERATE_SCRIPT)
        write(dir, "a/services.txt", "com.example.S\tcom.example.ImplA\t\t\n")
        write(dir, "b/services.txt", "com.example.S\tcom.example.ImplB\tvip\t\n")

        val result = run(dir, "generate")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generate")?.outcome)

        val classFile = File(dir, "build/spi/classes/com/example/generated/Config.class")
        assertTrue(classFile.isFile)
        val constantPool = String(classFile.readBytes(), Charsets.ISO_8859_1)
        assertTrue(constantPool.contains("com/example/ImplA"))
        assertTrue(constantPool.contains("com/example/ImplB"))
        assertTrue(constantPool.contains("top/jayvin/android/spi/ProviderConfiguration"))
    }

    @Test
    fun `generate fails when two providers compete for the same key`() {
        val dir = newProject(GENERATE_SCRIPT)
        write(dir, "a/services.txt", "com.example.S\tcom.example.ImplA\tvip\t\n")
        write(dir, "b/services.txt", "com.example.S\tcom.example.ImplB\tvip\t\n")

        val result = runAndFail(dir, "generate")
        assertTrue(result.output.contains("Duplicate registration"))
    }

    @Test
    fun `scan fails when a wildcard import leaves the service ambiguous`() {
        val dir = newProject(SCAN_SCRIPT)
        write(
            dir,
            "src/com/example/Impl.java",
            """
                package com.example;

                import com.thirdparty.*;

                @ServiceProvider(service = Missing.class)
                public class Impl {}
            """.trimIndent() + "\n",
        )

        val result = runAndFail(dir, "scan")
        assertTrue(result.output.contains("cannot determine the FQN"))
    }

    private companion object {
        val SCAN_SCRIPT = """
            import top.jayvin.android.spi.plugin.ScanProvidersTask

            tasks.register("scan", ScanProvidersTask) {
                sourceDirs.from(project.fileTree("src"))
                serviceConfigDirs.from(project.fileTree("resources"))
                manifestFile.set(layout.buildDirectory.file("spi/services.txt"))
                parseCacheFile.set(layout.buildDirectory.file("spi/parsed.txt"))
            }
        """.trimIndent()

        val GENERATE_SCRIPT = """
            import top.jayvin.android.spi.plugin.GenerateServiceConfigurationTask

            tasks.register("generate", GenerateServiceConfigurationTask) {
                packageName.set("com.example.generated")
                className.set("Config")
                manifests.from(file("a/services.txt"), file("b/services.txt"))
                outputDir.set(layout.buildDirectory.dir("spi/classes"))
            }
        """.trimIndent()
    }
}
