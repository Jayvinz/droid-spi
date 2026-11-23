package top.jayvin.android.spi.plugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Applies the plugin to a minimal Android application and runs a real AGP build.
 *
 * This is the only functional test that needs the Android SDK; it is skipped when neither
 * `local.properties` nor `ANDROID_HOME` points to an SDK. It complements the compat fixtures:
 * those build from `local-repo` against AGP 8.x, while this one runs the plugin under test
 * against the AGP version used by the main build.
 */
class AgpFunctionalTest : FunctionalTestBase() {

    @Test
    fun `AGP applies the plugin and ships the generated class`() {
        val environment = androidEnvironment()
        assumeTrue("Android SDK is not configured", environment != null)
        val dir = newAndroidProject(environment!!)

        val result = run(dir, "assembleDebug")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":DebugGenerateServiceConfiguration")?.outcome,
        )

        val manifest = File(dir, "build/generated/spi/debug/services.txt")
        assertTrue(manifest.isFile)
        assertTrue(
            manifest.readText().contains(
                "com.example.agpfunctional.Greeting\tcom.example.agpfunctional.GreetingImpl",
            ),
        )

        val apk = File(dir, "build/outputs/apk/debug")
            .listFiles()
            ?.singleOrNull { it.extension == "apk" }
            ?: error("the debug APK was not produced")
        ZipFile(apk).use { zip ->
            val found = zip.entries().asSequence()
                .filter { it.name.endsWith(".dex") }
                .any { entry ->
                    String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                        .contains("top/jayvin/android/spi/generated/ServiceConfiguration")
                }
            assertTrue("the generated class is missing from the APK", found)
        }
    }

    private class AndroidEnvironment(val sdk: File, val agpVersion: String)

    private fun androidEnvironment(): AndroidEnvironment? {
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "build.gradle.kts").isFile }
            ?: return null

        val sdkPath = File(repoRoot, "local.properties")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.trim().startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.trim()
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: return null

        val sdk = File(sdkPath).takeIf { it.isDirectory } ?: return null
        // Keep compileSdk in the test project in sync with the demo; skip when it is not installed.
        if (!File(sdk, "platforms/android-35").isDirectory) return null
        return AndroidEnvironment(sdk, agpVersion(repoRoot))
    }

    /** Keeps the test on the same AGP as the main build. */
    private fun agpVersion(repoRoot: File): String {
        val build = File(repoRoot, "build.gradle.kts").readText()
        return Regex("""id\("com\.android\.application"\)\s+version\s+"([^"]+)"""")
            .find(build)?.groupValues?.get(1)
            ?: DEFAULT_AGP_VERSION
    }

    private fun newAndroidProject(environment: AndroidEnvironment): File {
        val dir = temporaryFolder.newFolder("agp-project")
        File(dir, "settings.gradle").writeText("rootProject.name = 'spi-agp-functional'\n")
        File(dir, "local.properties").writeText(
            "sdk.dir=${environment.sdk.absolutePath.replace('\\', '/')}\n",
        )
        File(dir, "build.gradle").writeText(
            """
            buildscript {
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.android.tools.build:gradle:${environment.agpVersion}'
                    classpath files(${groovyClasspath()})
                }
            }

            apply plugin: 'com.android.application'
            apply plugin: 'top.jayvin.droidspi'

            repositories {
                google()
                mavenCentral()
            }

            android {
                namespace 'com.example.agpfunctional'
                compileSdk 35

                defaultConfig {
                    applicationId 'com.example.agpfunctional'
                    minSdk 24
                    targetSdk 35
                    versionCode 1
                    versionName '1.0'
                }
            }
            """.trimIndent(),
        )

        write(
            dir,
            "src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="SPI functional" />
            </manifest>
            """.trimIndent() + "\n",
        )

        // Minimal stand-ins for the published annotation/runtime so the Java sources compile and
        // the generated class resolves inside the test app. They are scanned like any other
        // source and contribute only harmless type names.
        write(
            dir,
            "src/main/java/top/jayvin/android/spi/ServiceProvider.java",
            """
            package top.jayvin.android.spi;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface ServiceProvider {
                Class<?> service();
                String key() default "";
            }
            """.trimIndent() + "\n",
        )
        write(
            dir,
            "src/main/java/top/jayvin/android/spi/ProviderConfiguration.java",
            """
            package top.jayvin.android.spi;

            public interface ProviderConfiguration {
                Class<?>[] services();
                String[] keys();
                Class<?> providerAt(int index);
                Object newProviderAt(int index);
            }
            """.trimIndent() + "\n",
        )
        write(
            dir,
            "src/main/java/com/example/agpfunctional/Greeting.java",
            """
            package com.example.agpfunctional;

            public interface Greeting {
                String text();
            }
            """.trimIndent() + "\n",
        )
        write(
            dir,
            "src/main/java/com/example/agpfunctional/GreetingImpl.java",
            """
            package com.example.agpfunctional;

            import top.jayvin.android.spi.ServiceProvider;

            @ServiceProvider(service = Greeting.class)
            public class GreetingImpl implements Greeting {
                @Override
                public String text() {
                    return "hello";
                }
            }
            """.trimIndent() + "\n",
        )
        return dir
    }

    private companion object {
        const val DEFAULT_AGP_VERSION = "9.5.1"
    }
}
