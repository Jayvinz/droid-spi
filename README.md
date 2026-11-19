# Droid-spi

Compile-time service discovery for Android. Declare providers with `@ServiceProvider` (or standard
`META-INF/services` files) and look them up at runtime **without reflection** — including providers
that live in on-demand dynamic feature modules.

- **No reflection, no APT/KSP** — providers are referenced through class literals.
- **Per-variant tables** — `freeDebug`, `paidDebug`, … each get their own configuration.
- **Dynamic delivery aware** — a provider is `MISSING` until its module is installed; the next
  `load()` then works, with no `refresh()`.
- **Never throws** — unavailable lookups return `null` / an empty collection.

中文文档：[README.zh-CN.md](README.zh-CN.md)

## Integration

The plugin resolves from the **Gradle Plugin Portal**, the libraries from **Maven Central**. No
custom repository or token is needed.

```kotlin
// settings.gradle.kts — only if your build does not already declare them
pluginManagement { repositories { gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
```

```kotlin
// module build.gradle.kts
plugins {
    // supported module types: application, library, dynamic-feature
    id("top.jayvin.droidspi") version "1.0.0-alpha03"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha03")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha03")
}
```

Apply the plugin to every module that declares providers (and to the application module, which also
aggregates them). The `runtime` dependency belongs in the application module; provider modules only
need `annotation`.

## Declare a provider

The service interface lives in a base / resident module:

```kotlin
interface HomeService {
    fun title(): String
}
```

Implementations live wherever they belong:

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {
    override fun title() = "Home"
}

@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

Pure Java: `@ServiceProvider(service = HomeService.class)`. Libraries that already ship a JDK SPI
descriptor can be integrated by placing the `META-INF/services/<interface FQN>` file under the
module's source resources.

## Use

```kotlin
// optional: initialize eagerly in Application.onCreate; otherwise the first lookup does it
ServiceLoader.initialize()

val home = ServiceLoader.load(HomeService::class.java)         // default provider, or null
val vip  = ServiceLoader.load(HomeService::class.java, "vip")  // keyed variant, or null
val all  = ServiceLoader.loadAll(HomeService::class.java)      // key -> instance
val st   = ServiceLoader.stateOf(HomeService::class.java)      // READY / MISSING / DEAD
```

Kotlin also has `ServiceLoader.load<HomeService>()`. `providers()` / `stream()` enumerate every
entry; `loadClass()` returns the class without initializing it.

### Dynamic features

Declare the module in the app (`dynamicFeatures += setOf(":modules:feature_camera")`) and apply the
plugin to it. Before installation its provider is `MISSING`; after installation the next `load()`
returns an instance. No install listener, no refresh.

### Several default implementations

The empty key may be shared by more than one implementation. `load()` returns the first available,
ordered by `(service, key, provider FQN)`; `providers()` enumerates all of them. Any **non-empty**
key stays unique — duplicates fail the build.

## Learn more

- Design and implementation: [docs/droid-spi-design.md](docs/droid-spi-design.md)
  ([中文](docs/droid-spi-design.zh-CN.md))
- Articles: [Introducing Droid-spi](docs/articles/01-introducing-droid-spi.md) ·
  [How it works internally](docs/articles/02-how-droid-spi-works.md)
  ([中文](docs/articles/01-introducing-droid-spi.zh-CN.md) ·
  [中文](docs/articles/02-how-droid-spi-works.zh-CN.md))

## Limitations

- Service interfaces must live in base / resident modules, not in dynamic features.
- No nested classes or `typealias` as service interfaces.
- Requires JDK 17, AGP 8.x, `minSdk 24`.
