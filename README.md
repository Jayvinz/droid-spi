# droid-spi

[![CI](https://github.com/Garenzhou/android-spi/actions/workflows/ci.yml/badge.svg)](https://github.com/Garenzhou/android-spi/actions/workflows/ci.yml)

Compile-time service discovery for Android. Declare providers with `@ServiceProvider` or plain
`META-INF/services` files; a Gradle plugin generates a fixed-name configuration class that the
runtime resolves **without reflection** — including providers that live in on-demand dynamic
feature modules.

- **No reflection, no APT/KSP**: providers are referenced through class literals.
- **Per-variant tables**: `freeDebug`, `paidDebug`, … each get their own configuration class.
- **Dynamic delivery friendly**: a provider is `MISSING` until its module is installed, then the
  next `load()` works — no `refresh()`.
- **Total facade**: lookups never throw; unavailability is `null` / empty.

中文文档：[README.zh-CN.md](README.zh-CN.md)

## Install

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/Garenzhou/android-spi")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/Garenzhou/android-spi")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        google()
        mavenCentral()
    }
}
```

`gpr.user` is your GitHub username and `gpr.key` a token with `read:packages` (GitHub Packages
requires authentication even for public artifacts). Put both in your **user-level**
`~/.gradle/gradle.properties`, or export `GITHUB_ACTOR` / `GITHUB_TOKEN` — never commit them to
the repository.

```kotlin
// module build.gradle.kts
plugins {
    id("com.android.application")     // or com.android.library / com.android.dynamic-feature
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

## Use

**1. Declare a provider** — the service interface must live in the base module.

```kotlin
// :core-api
interface HomeService { fun title(): String }

// :feature-home
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService { override fun title() = "Home" }

// a second implementation under a variant key
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

Pure Java works the same way: `@ServiceProvider(service = HomeService.class)`.

**2. Initialize once** in `Application.onCreate`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
```

**3. Look up** where you need it:

```kotlin
val home = ServiceLoader.load(HomeService::class.java)        // default provider, or null
val vip  = ServiceLoader.load(HomeService::class.java, "vip") // keyed variant
val all  = ServiceLoader.loadAll(HomeService::class.java)     // key -> instance
val cls  = ServiceLoader.loadClass(HomeService::class.java)   // class only, no <clinit>
val st   = ServiceLoader.stateOf(HomeService::class.java)     // READY / MISSING / DEAD

ServiceLoader.stream(HomeService::class.java).forEach { provider ->
    Log.d("demo", provider.type().name)
}
```

Kotlin also has `ServiceLoader.load<HomeService>()`. Every lookup returns `null` / an empty
collection when a provider is unavailable; nothing throws.

### Standard `META-INF/services`

Libraries that already ship a JDK SPI file can be integrated without touching their code: put the
file under the module's source resources and the plugin picks it up (`spi { scanMetaInfServices =
true }` is the default). Entries map to the default key and require a public no-arg constructor.

### Dynamic feature modules

```kotlin
android { dynamicFeatures += setOf(":modules:feature_camera") }
```

Apply the plugin to the dynamic feature too (it only needs the `annotation` artifact at compile
time). It contributes providers like any other module. Before installation its provider is
`MISSING`; after installation the next `load()` returns an instance. There is no install listener
and no `refresh()`.

## Build from source

```bash
# local-repo/ is gitignored; the flag is needed only on a fresh clone
./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand

./gradlew :runtime:testDebugUnitTest
./gradlew :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
```

Requirements: JDK 17, Android Gradle Plugin 8.x, `minSdk 24`.

## How it works (advanced)

```
per module, per variant                app, per variant                  runtime
@ServiceProvider / META-INF  ──scan──▶ services.txt ──aggregate──▶ ServiceConfiguration.class
                                                                             │
                                                          Application.onCreate └─ ServiceLoader.initialize()
                                                                             │
                                                          next load() after install ──▶ provider instance
```

1. Every module variant scans its own source directories (main + build-type + flavor overlays)
   and publishes the result as a `spi-manifest`-typed Gradle artifact on its runtime elements.
2. The application variant reads those artifacts through variant-aware resolution, validates
   `(service, key)` uniqueness, and emits `ServiceConfiguration` bytecode with ASM. The class has
   no `<clinit>`: provider class literals appear only inside method bodies, so an uninstalled
   dynamic module cannot poison the table.
3. At runtime, `ProviderRegistry` probes on demand. `NoClassDefFoundError` becomes `null`/`MISSING`
   and is retried next time; only `<clinit>` failures (`DEAD`) are cached.

Full design, invariants, cache/isolation notes and the regression matrix:
[docs/droid-spi-design.md](docs/droid-spi-design.md) · [中文](docs/droid-spi-design.zh-CN.md).

Long-form articles: [Introducing droid-spi](docs/articles/01-introducing-droid-spi.md) ·
[How it works internally](docs/articles/02-how-droid-spi-works.md)
([中文](docs/articles/01-introducing-droid-spi.zh-CN.md) ·
[中文](docs/articles/02-how-droid-spi-works.zh-CN.md)).

## Limitations

- Service interfaces must live in the base / resident modules, not in dynamic features.
- Exactly one provider per `(service, key)`; duplicates fail the build.
- No nested classes or `typealias` as service interfaces.
- R8 needs `-dontwarn` for modules that may be absent (see `app/proguard-rules.pro`).
