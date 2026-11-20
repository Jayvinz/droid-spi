# Droid-spi

中文文档：[README.zh-CN.md](README.zh-CN.md)

**Compile-time service discovery for Android.** Declare providers with `@ServiceProvider` (or standard
`META-INF/services` files) and look them up at runtime **without reflection** — including providers
that live in on-demand dynamic feature modules.

- **Zero reflection** — providers are referenced through class literals; no `Class.forName`, no
  reflective construction, friendly to mobile runtime performance.
- **No APT/KSP** — does not require time-consuming annotation-processing tasks such as
  KAPT/KSP; this keeps large builds fast: incremental builds, build cache and isolated projects
  are supported, and every variant / build type is handled.
- **Keyed implementations** — one interface can have several implementations; select the one you
  need by `key` at lookup time.
- **Never throws** — unavailable lookups return `null` / an empty collection.


## Integration

### Supported versions

| Component | Supported |
|---|---|
| AGP | **8.0 – 8.13 and 9.0 – 9.5** (AGP 7.x is not supported) |
| Gradle | the version AGP requires: 8.0+ for AGP 8.0–8.6, 8.9 for AGP 8.7, 8.13 for AGP 8.13, 9.1 for AGP 9.0, 9.6+ for AGP 9.5 |
| JDK | 17 |
| Kotlin | 1.8+ for projects that use `runtime` |
| Android | `minSdk 24` |

`runtime` publishes Kotlin 2.0 metadata, which Kotlin 1.8 through 2.x compilers can read (the
metadata compatibility rule accepts the next major release at `x.0.0`). The SPI Gradle plugin
itself does not require KGP, and Java-only consumers are unaffected.

On AGP 9 Kotlin is built into AGP: do not apply `org.jetbrains.kotlin.android`, or the build
fails. Configure the JVM target through `kotlin { compilerOptions { jvmTarget } }`.

The plugin resolves from the **Gradle Plugin Portal**, the libraries from **Maven Central**. No
custom repository or token is needed.

```kotlin
// settings.gradle.kts — only if your build does not already declare them
pluginManagement {
    repositories {
        gradlePluginPortal()   // plugin repository
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()         // annotation / runtime repository
    }
}
```

```kotlin
// module build.gradle.kts
plugins {
    // apply this plugin to every module that declares providers
    // supported module types: application, library, dynamic-feature
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    // provider modules only need annotation.
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    // application module, or anywhere else that uses ServiceLoader
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

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
  ([Chinese](docs/droid-spi-design.zh-CN.md))
- Articles: [Introducing Droid-spi](docs/articles/01-introducing-droid-spi.md) ·
  [How it works internally](docs/articles/02-how-droid-spi-works.md)
  ([Chinese](docs/articles/01-introducing-droid-spi.zh-CN.md) ·
  [Chinese](docs/articles/02-how-droid-spi-works.zh-CN.md))

## Limitations

- Service interfaces must live in base / resident modules, not in dynamic features.
- No nested classes or `typealias` as service interfaces.
- Requires JDK 17, AGP 8.0–8.13 or 9.x, `minSdk 24`. AGP 7.x is not supported.
