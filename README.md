# Droid-spi

**Compile-time service discovery for multi-module Android apps.**

中文文档：[README.zh-CN.md](README.zh-CN.md) · `1.0.0-alpha02` · JDK 17 · AGP 8.0–9.5 · minSdk 24

Declare a service interface as the contract betwee&#x6E;*&#x20;modules, implement it in any module, and look  
it up at runtime. No reflection, no annotation processing, and providers that&#x20;*&#x6C;ive in on-demand  
dynamic feature modules resolve the moment they are installed.

- **Cross-module contracts.** The contract interface lives in the base module, business module A  
  implements it, and business module B calls it without depending on A or seeing any of A's code.
- **Zero reflection.** Providers and the generated table reference each other through class  
  literals and direct construction, which keeps the call path cheap on mobile runtimes.
- **R8-friendly.** The shrinker sees the generated table as ordinary code, so no `-keep` rules are  
  needed for it.
- **No APT/KSP.** No annotation-processing round trips, so large builds stay fast. Incremental  
  builds, build cache, isolated projects, and every variant / build type are supported.
- **Keyed implementations.** One interface can have several implementations; pick the one you need  
  by `key` at lookup time.
- **Never throws.** An unavailable lookup returns `null` or an empty collection.

## Contents

- [Why Droid-spi](#why-droid-spi)
- [Quick start](#quick-start)
- [Requirements](#requirements)
- [Installation](#installation)
- [Declaring a provider](#declaring-a-provider)
  - [The contract](#the-contract)
  - [Implementations](#implementations)
  - [Custom construction with `@ProviderFactory`](#custom-construction-with-providerfactory)
  - [Standard `META-INF/services` descriptors](#standard-meta-infservices-descriptors)
- [Loading services](#loading-services)
  - [API surface](#api-surface)
  - [Lifetime, caching, and failure semantics](#lifetime-caching-and-failure-semantics)
- [Dynamic features](#dynamic-features)
- [Flavors and build types](#flavors-and-build-types)
- [Several default implementations](#several-default-implementations)
- [Gradle DSL](#gradle-dsl)
- [How it works](#how-it-works)
- [How it compares](#how-it-compares)
- [Project layout](#project-layout)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)
- [Learn more](#learn-more)
- [Development](#development)
- [License](#license)

## Why Droid-spi

Large multi-module builds are dependency-isolated by design. Business module B needs behavior that  
lives in business module A, yet B must not depend on A and should not know anything about A's  
implementation. The service interface, defined in the base module, is the contract between them:  
module A implements it, module B loads the instance through the framework, and the build-time table  
connects the two sides at runtime.

```
business module B (caller) ──depends on──▶ base (contract interface) ◀──depends on── business module A (implementation)
```

B never references A's code. Which module implements the contract, and whether that module is even  
installed, never leaks into the call site.

## Quick start

**1. Apply the plugin** to the application module and to every module that declares providers:

```kotlin
// module build.gradle.kts
plugins {
    // supported module types: application, library, dynamic-feature
    id("top.jayvin.droidspi") version "1.0.0-alpha02"
}
```

**2. Declare the contract in the base module, implement it in any other module:**

```kotlin
// :modules:base — the contract. Callers and implementors depend on this module only.
interface FeedSource {
    fun load(page: Int, pageSize: Int): FeedPage
}

// :modules:community — implements the contract; callers never reference this module
@ServiceProvider(service = FeedSource::class)
class RecommendFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.recommend(), page, pageSize,
    )
}
```

**3. Load it where you need it.** The caller imports only the interface:

```kotlin
val feed = ServiceLoader.load<FeedSource>()
recycler.submitList(feed?.load(page = 0, pageSize = 30)?.videoIds.orEmpty())
```

That is the whole setup. Repositories, dependencies, and the rest of the surface follow below.

## Requirements

| Requirement           | Value                                                                           |
| --------------------- | ------------------------------------------------------------------------------- |
| JDK                   | 17                                                                              |
| Android Gradle Plugin | 8.0–8.13, 9.0–9.5 (AGP 7.x is not supported)                                    |
| Gradle                | the version your AGP release requires (9.6 in this repository)                  |
| `minSdk`              | 24                                                                              |
| Module types          | `com.android.application`, `com.android.library`, `com.android.dynamic-feature` |
| Provider language     | Kotlin or Java; no KAPT/KSP, no code generation visible to your build scripts   |

On AGP 9 and newer the Kotlin Android plugin is built in: do **not** apply  
`org.jetbrains.kotlin.android` in a module that uses AGP 9, because the build fails when it is  
present. Modules only need `compileOptions`; built-in Kotlin derives `jvmTarget` from it.

## Installation

The plugin resolves from the Gradle Plugin Portal, the libraries from Maven Central. No custom  
repository or token is needed.

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

| Artifact      | Coordinate                        | Where         | Needed by                                                    |
| ------------- | --------------------------------- | ------------- | ------------------------------------------------------------ |
| Gradle plugin | `top.jayvin.droidspi` (plugin id) | Plugin Portal | app + every provider module                                  |
| Annotations   | `top.jayvin.droidspi:annotation`  | Maven Central | provider modules                                             |
| Runtime       | `top.jayvin.droidspi:runtime`     | Maven Central | application module, or any module that calls `ServiceLoader` |

```kotlin
// module build.gradle.kts
dependencies {
    // provider modules only need annotation
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha02")
}

// app/build.gradle.kts
dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha02")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha02")
}
```

`annotation` and `runtime` both ship Kotlin 2.0 metadata, so consumers on Kotlin 1.8+ can compile  
against them. Keep the plugin, `annotation`, and `runtime` versions aligned.

## Declaring a provider

### The contract

Service interfaces are the seam between modules, so they belong in a base or resident module that  
both the caller and the implementor already compile against:

```kotlin
// :modules:base
interface FeedSource {
    fun load(page: Int, pageSize: Int): FeedPage
}
```

They must be interfaces, and they must not come from a dynamic feature module; see  
[Limitations](#limitations).

### Implementations

A plain implementation needs nothing but the annotation. The module applies the plugin and depends  
on `annotation`; no caller depends on it:

```kotlin
// :modules:community
@ServiceProvider(service = FeedSource::class)
class RecommendFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.recommend(), page, pageSize,
    )
}
```

The same interface can carry a keyed variant. Callers pick the one they need at lookup time:

```kotlin
@ServiceProvider(service = FeedSource::class, key = "following")
class FollowingFeedSource : FeedSource {
    override fun load(page: Int, pageSize: Int) = pagedIds(
        CommunityRepository.following(), page, pageSize,
    )
}
```

Kotlin only accepts named arguments for Java annotations, so always write  
`@ServiceProvider(service = FeedSource::class)`. Pure Java is the same annotation with a class  
literal: `@ServiceProvider(service = FeedSource.class)`.

### Custom construction with `@ProviderFactory`

The generated table normally instantiates a provider with `new Impl()`. Annotating a static,  
no-argument method with `@ProviderFactory` hands construction to the provider class instead. It is  
also required when the constructor is private or takes arguments: an account service that needs the  
session from your app graph cannot be built with `new Impl()`.

The generated bytecode calls the factory with `INVOKESTATIC` (still zero reflection), and the method  
may return any instance the class already owns: a singleton, a DI container object, or a pre-built  
object. The framework does not cache instances, so the factory runs on every `load()` /  
`Provider.get()`; return a cached field when the same object should be shared.

```kotlin
@ServiceProvider(service = AccountService::class)
class AccountServiceImpl private constructor(
    private val session: UserSession,
) : AccountService {
    override fun userName() = session.currentUser.name
    override fun level() = session.currentUser.membershipLabel

    companion object {
        // Created once and handed to every caller; the framework itself never caches.
        private val shared by lazy { AccountServiceImpl(AppGraph.userSession) }

        // Kotlin requires @JvmStatic inside a companion object; at most one factory per class.
        @ProviderFactory @JvmStatic
        fun create(): AccountServiceImpl = shared
    }
}
```

```java
@ServiceProvider(service = AccountService.class)
public class AccountServiceImpl implements AccountService {
    private static final AccountServiceImpl INSTANCE = new AccountServiceImpl();

    @ProviderFactory
    public static AccountServiceImpl create() { return INSTANCE; }
}
```

The factory must be `static`, take no arguments, and return the provider class itself (not the  
interface), since the generator emits `INVOKESTATIC Impl.create()LImpl;`. Build-time checks enforce  
the static form and at most one factory per class; the no-argument and return-type rules are not  
checked, and a mismatch fails as `NoSuchMethodError` on the first load. A factory that throws makes  
that `load()` return `null`, and the call is retried next time.

The example's `LocalAccountService` uses exactly this shape: its private constructor takes the  
application context, and `create()` returns one lazily created instance.

### Standard `META-INF/services` descriptors

Libraries that already ship a JDK SPI descriptor can be integrated without touching their code:  
place `META-INF/services/<interface FQN>` under the module's source resources (typically  
`src/main/resources/`). Only text manifests are read; dependency jars are not unzipped and their  
classes are not scanned. The DroidReel example declares its seeded media catalog this way.


## Loading services

```kotlin
// optional: initialize eagerly in Application.onCreate; otherwise the first lookup does it
ServiceLoader.initialize()

// feed screen: the default implementation, or null when it is missing
val feed = ServiceLoader.load<FeedSource>()
recycler.submitList(feed?.load(page = 0, pageSize = 30)?.videoIds.orEmpty())

// following tab: same interface, different key
val following = ServiceLoader.load<FeedSource>("following")

// account center: the only implementation, null when the account module is absent
val account = ServiceLoader.load<AccountService>()
```

### API surface

`ServiceLoader` mirrors `java.util.ServiceLoader` where it can. Java callers pass class literals
(every method is `@JvmStatic`); Kotlin also has the inline `load<T>()` form.

| Call | Returns | Notes |
|---|---|---|
| `initialize()` | `Unit` | Loads the table eagerly; optional and idempotent |
| `isInitialized()` | `Boolean` | Whether the table has been loaded, including the empty fallback |
| `load(service[, key])` / `load<T>([key])` | `T?` | Instantiates the provider; `null` when unavailable |
| `providerOf(service[, key])` | `Provider<T>?` | Handle without instantiating |
| `findFirst(service)` | `Provider<T>?` | Default key first, then any other available entry |
| `providers(service)` / `stream(service)` | `List<Provider<T>>` / `Sequence<Provider<T>>` | Every available entry, including several defaults |
| `loadClass(service[, key])` | `Class<out T>?` | Resolves the class without triggering its `<clinit>` |
| `loadAll(service)` | `Map<String, T>` | `key -> instance`; one entry per key |
| `loadAllClasses(service)` | `List<Class<out T>>` | Every available provider class |
| `stateOf(service[, key])` | `ProviderState?` | `READY` / `MISSING` / `DEAD`; `null` when unregistered |
| `snapshot()` | `List<Triple<String, String, ProviderState>>` | Debugging view; re-probes on every call |
| `DEFAULT_KEY` | `""` | The default implementation's key |

A `Provider<S>` handle exposes `type()` (the implementation class), `get()` (an instance), and
`state()`. Handles returned by `stream()` / `findFirst()` are `READY` when created, and `get()` can
still return `null` later if the provider's module is uninstalled.

### Lifetime, caching, and failure semantics

- **Instances are not cached.** Every `load()` / `Provider.get()` constructs the provider again or
  calls its `@ProviderFactory`. Only the configuration table is loaded once and kept.
- **The table loads lazily** on the first lookup (thread-safe, double-checked) or eagerly through
  `initialize()`. A missing or invalid `ServiceConfiguration` is logged and degraded to an empty
  table rather than raised.
- **`DEAD` is cached per entry**, because a failed `<clinit>` marks the class erroneous for the
  lifetime of the process. `MISSING` is never cached, so an installed module is visible on the next
  call with no refresh and no install listener.
- **Nothing throws.** Unavailability is a normal state: `null` or an empty collection.
- **Default-key fallback.** When the default key has nothing available and the service has exactly
  one available non-default key, `load(service)` returns that provider. With two or more available
  keys there is no fallback; ask for the key explicitly.
- **Ordering.** Entries are ordered by `(service, key, provider FQN)`, and the first available entry
  wins. Use `loadAll` / `providers` when you need all of them.

## Dynamic features

An on-demand feature cannot be compiled against, so the base app cannot reference
`ProductionEntryImpl`. Declare the module in the app (`dynamicFeatures += setOf(":modules:production")`),
apply the plugin to it, and let the implementation open its own screen; callers never learn the
`ProductionHubActivity` class name:

```kotlin
// :modules:production — the contract call works even when this module is not installed yet
@ServiceProvider(service = ProductionEntry::class)
class ProductionEntryImpl : ProductionEntry {
    override fun openCreate(context: Context) {
        context.startActivity(Intent(context, ProductionHubActivity::class.java))
    }
}
```

A Create button then only has to probe:

```kotlin
fun onCreateClick(context: Context) {
    val production = ServiceLoader.load(ProductionEntry::class.java)
    if (production != null) {
        production.openCreate(context)   // installed: the implementation opens its own screen
    } else {
        installProduction()              // request the "production" split via Play Feature Delivery
    }
}
```

Before installation the provider state is `MISSING`; after installation the next `load()` returns an
instance. No install listener, no refresh.

## Flavors and build types

Each variant gets its own table, so implementations follow the source sets a real app already uses.
The example's `free` / `vip` flavor pair each provide `ChannelService` from `src/free` and `src/vip`,
and a debug-only `DemoInfoService` under `app/src/debug` simply has no entry in release tables. A
`vip` build only ever sees the `vip` provider. Nothing to wire.

Aggregation is per variant, not per build type: `freeDebug` and `vipDebug` compile disjoint flavor
sources and get separate tables.

## Several default implementations

The empty key may be shared by more than one implementation. `load()` returns the first available,
ordered by `(service, key, provider FQN)`; `providers()` enumerates all of them. Any non-empty key
stays unique, and duplicates fail the build.

## Gradle DSL

The plugin registers an `spi { }` extension in every module it is applied to:

```kotlin
spi {
    // false: this module neither scans nor generates. Useful when bisecting build issues.
    enabled = true

    // false: ignore META-INF/services/*.txt under this module's source directories.
    scanMetaInfServices = true
}
```

## How it works

1. **Scan, per module and per variant.** The plugin registers `{variantName}ScanProviders`, which
   reads `@ServiceProvider` annotations and `META-INF/services` files from `src/main` plus the
   variant's build-type and flavor overlays, and writes
   `build/generated/spi/{variantName}/services.txt`.
2. **Merge, in application modules only.** `{variantName}GenerateServiceConfiguration` collects the
   manifests of every compatible module variant, validates key uniqueness, and generates
   `top.jayvin.android.spi.generated.ServiceConfiguration` with ASM.
3. **Resolve, at runtime.** `ServiceLoader` instantiates that class on the first lookup and probes
   providers on demand from the index-addressable table.

Manifests travel through Gradle's variant-aware artifact resolution (`spi-manifest` artifacts on
`{variant}RuntimeElements`), not a cross-project collector, so the scan → generate edges survive
configuration-cache reuse and isolated projects.

The generated class deliberately has **no static initializer**: provider class literals appear only
inside method bodies. Otherwise an uninstalled dynamic module would mark the configuration class
erroneous and break the whole table for the rest of the process. The same reasoning keeps service
interfaces in base modules.

## How it compares

Every multi-module project runs into the same wall. The usual fixes all leave something to carry:

- **String class names plus reflection** (`Class.forName`) break silently on rename, need R8 `-keep`
  rules, and surface a missing module as `ClassNotFoundException`.
- **A hand-written registry** avoids reflection, but every feature must remember to register, some
  initializer must run in the right order, and keyed variants, build types, and dynamic modules all
  become hand-written branching.
- **JDK `ServiceLoader` / AutoService** reads `META-INF/services` reflectively, so the rename and R8
  problems stay; there is no `key`, no variant awareness, and a missing provider throws
  `ServiceConfigurationError`. AutoService replaces the descriptor with an APT step.
- **Hilt or Koin** solve wiring inside a closed graph, which is the wrong shape here: a dynamic module
  that is not installed yet is not a node the graph can compile against.

|  | Reflection | Keyed variants | On-demand modules | Build cost |
|---|---|---|---|---|
| String class names | `Class.forName` | hand-written | hand-written | none |
| Hand-written registry | none | hand-written | install callbacks, manual refresh | none |
| JDK `ServiceLoader` / AutoService | reflective construction | none | throws `ServiceConfigurationError` | APT (AutoService) |
| DI (Hilt / Koin) | none | multibindings | outside the compiled graph | KSP / KAPT (Hilt) |
| **Droid-spi** | none | `key`, per-variant tables | next `load()` returns it | one scan, no APT |

Droid-spi keeps the `ServiceLoader` call shape and moves the table to build time.

## Project layout

The library itself is three published modules, plus one build-time placeholder:

| Path | Role |
|---|---|
| `plugin/` | Gradle plugin. Scans each variant's sources, merges the manifests, generates the `ServiceConfiguration` bytecode table. |
| `annotation/` | `@ServiceProvider` and `@ProviderFactory`. `CLASS` retention, zero dependencies; the annotations never enter the runtime. |
| `runtime/` | The `ServiceLoader` facade and its registry. Looks providers up in the generated table, zero reflection. |
| `configuration-stub/` | `compileOnly` placeholder for the generated class name. Not published, never packaged. |

Everything else is the integration example: `app/` is **DroidReel**, a Compose short-video demo with
two `free` / `vip` flavors × two build types, and `modules/` supplies its contracts, account,
community, and on-demand production modules. The profile screen's **SPI status** dialog lists the
generated table for on-device verification. `compat/` holds the AGP 8.0 / 8.7 compatibility
fixtures. Build and run notes are in [docs/example-app.md](docs/example-app.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:app` fails to resolve the plugin right after cloning | `local-repo/` is gitignored and empty | `./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand` |
| A plugin change has no effect in the demo | The demo consumes the published artifact, not the project | Publish again, then rebuild; delete `{Variant}GenerateServiceConfiguration` intermediates if stale bytecode persists |
| Build fails on a duplicate key | A non-empty `key` must be unique per service | Rename one key, or drop it to use the default slot |
| Build fails asking for an explicit import | `import a.b.*` cannot be resolved to a service type | Import the type explicitly or use its FQN |
| `NoSuchMethodError` on the first load | `@ProviderFactory` is not static/no-arg, or returns the interface | Make it `static`/`@JvmStatic`, no arguments, returning the implementation class |
| A provider never shows up at runtime | The module does not apply the plugin, or its flavor dimension does not match the app | Apply the plugin to every provider module; mirror the app's flavor dimensions |
| `NoClassDefFoundError` / missing class warnings under R8 | Providers of uninstalled modules are absent at shrink time | Add `-dontwarn` for those modules when you enable minification |
| A service interface lives in a dynamic feature | Interfaces must be resident for the table to resolve | Move the interface to a base module |

For anything that looks like a framework defect, run the verification matrix in
[docs/droid-spi-design.md](docs/droid-spi-design.md) §8.1 before filing.

## Limitations

- Service interfaces must live in base / resident modules, not in dynamic features.
- Interfaces only: no nested classes or `typealias` as a service interface, and no abstract classes.
- A non-empty key is unique per service across the whole build.
- AGP 7.x is unsupported; AGP 8.0–8.13 and 9.0–9.5 share one plugin artifact.

## Learn more

- Design and implementation: [docs/droid-spi-design.md](docs/droid-spi-design.md)
  ([Chinese](docs/droid-spi-design.zh-CN.md))
- Getting started:
  [English](docs/articles/01-droid-spi-getting-started.en.md) ·
  [Chinese](docs/articles/01-droid-spi-getting-started.zh-CN.md)
- Plugin internals:
  [English](docs/articles/02-droid-spi-plugin-internals.en.md) ·
  [Chinese](docs/articles/02-droid-spi-plugin-internals.zh-CN.md)
- Earlier long-form series: [Introducing Droid-spi](docs/articles/01-introducing-droid-spi.md)
  ([Chinese](docs/articles/01-introducing-droid-spi.zh-CN.md)) ·
  [How it works internally](docs/articles/02-how-droid-spi-works.md)
  ([Chinese](docs/articles/02-how-droid-spi-works.zh-CN.md))
- Example app: [docs/example-app.md](docs/example-app.md)

## Development

```bash
# After changing annotation / runtime / plugin, publish to local-repo first: the app and
# modules consume the published artifacts, not project dependencies.
./gradlew :annotation:publish :runtime:publish :plugin:publish

./gradlew :app:assembleFreeDebug          # one variant (flavors: free/vip; types: debug/release)
./gradlew :runtime:testDebugUnitTest      # runtime unit tests (plain JVM, no device needed)
./gradlew :plugin:test                    # manifest merge / key validation tests
```

On Windows use `gradlew.bat`. To inspect the generated table:

```bash
javap -p -c app/build/intermediates/classes/freeDebug/FreeDebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class
```

Releases are cut by pushing a `v*` tag; the workflow verifies the tag against `spiVersion` in
`gradle.properties`, publishes `annotation` / `runtime` to Maven Central and the plugin to the Gradle
Plugin Portal. See [CLAUDE.md](CLAUDE.md) for the full release and compatibility notes.

## License

Apache License 2.0, copyright 2026 Jayvin. The full text is in [LICENSE](LICENSE).
