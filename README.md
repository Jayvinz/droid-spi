# Droid-spi

中文文档：[README.zh-CN.md](README.zh-CN.md)

**Compile-time service discovery for multi-module Android apps.** Declare a service interface as
the contract between modules, implement it in any module, and look it up at runtime without
reflection or annotation processing, including providers that live in on-demand dynamic feature
modules.

- Cross-module contracts: modules stay dependency-isolated. The contract interface lives in the
  base module; business module A implements it; business module B loads the service instance and
  calls business logic on it without depending on A or seeing any of A's code.
- Zero reflection: providers are referenced through class literals rather than `Class.forName` or
  reflective construction, which is friendly to mobile runtime performance.
- R8-friendly: the generated table references providers through class literals and direct
  construction, so the shrinker sees them as ordinary code. No extra R8 keep configuration is
  needed.
- No APT/KSP: the plugin does not require time-consuming annotation-processing tasks such as
  KAPT/KSP, which keeps large builds fast. It supports incremental builds, build cache and
  isolated projects, and handles every variant / build type.
- Keyed implementations: one interface can have several implementations; select the one you
  need by `key` at lookup time.
- Never throws: unavailable lookups return `null` / an empty collection.


## Integration

The plugin resolves from the Gradle Plugin Portal, the libraries from Maven Central. No
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
    id("top.jayvin.droidspi") version "1.0.0-alpha02"
}

dependencies {
    // provider modules only need annotation.
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha02")
    // application module, or anywhere else that uses ServiceLoader
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha02")
}
```

## Declare a provider

A shopping app split into feature modules makes a good example: a home feed, an account center,
and a camera page that ships in an on-demand module. The `app/` + `modules/` integration example
in this repo runs the same code.

Large multi-module builds are dependency-isolated by design: business module B needs behavior
that lives in business module A, yet B must not depend on A and should not know anything about
A's implementation. The service interface, defined in the base module, is the contract between
them. Module A implements the contract; module B loads the service instance through the
framework and calls business logic on it:

```
business module B (caller) ──depends on──▶ base (contract interface) ◀──depends on── business module A (implementation)
```

B never references A's code; the build-time table is what connects the two sides at runtime. The
contract lives in a base / resident module, so every feature can compile against it:

```kotlin
// :modules:core-api — the contract. Callers and implementors both depend on this module only.
interface HomeService {
    fun title(): String
    fun recommendations(): List<String>
}
```

Implementations live wherever they belong. A plain one needs nothing but the annotation. No
caller ever depends on this module:

```kotlin
// :modules:feature-home — implements the contract; callers never reference this module
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {
    override fun title() = "Home"
    override fun recommendations() = listOf("Running shoes on sale", "New arrivals this week")
}
```

The same interface can carry a keyed variant. Callers pick the one they need at lookup time:

```kotlin
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    override fun title() = "Home · VIP"
    override fun recommendations() = listOf("Members-only drop", "Free shipping voucher")
}

@ServiceProvider(service = AccountService::class, key = "guest")
class GuestAccountServiceImpl : AccountService {
    override fun userName() = "Guest"
    override fun level() = "Not signed in"
}
```

Pure Java: `@ServiceProvider(service = HomeService.class)`. Libraries that already ship a JDK SPI
descriptor can be integrated by placing the `META-INF/services/<interface FQN>` file under the
module's source resources.

### Construction and `@ProviderFactory`

The generated table normally instantiates a provider with `new Impl()`. Annotating a static,
no-argument method with `@ProviderFactory` hands construction to the provider class instead. It
is also required when the constructor is private or takes arguments: an account service that
needs the session from your app graph cannot be built with `new Impl()`. The generated bytecode
calls the factory with `INVOKESTATIC` (still zero reflection), and the method may return any
instance the class already owns: a singleton, a DI container object, or a pre-built object. The
framework does not cache instances, so the factory is called on every `load()` / `Provider.get()`;
return a cached field when the same object should be shared.

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
interface), since the generator emits `INVOKESTATIC Impl.create()LImpl;`. Build-time checks enforce the
static form and at most one factory per class; the no-argument and return-type rules are not
checked, and a mismatch fails as `NoSuchMethodError` on the first load. `stateOf()` / `loadClass()`
still resolve the provider class literal, so availability and dynamic-feature behavior are
unchanged. A factory that throws makes that `load()` return `null` and is retried on the next call.

## Use

```kotlin
// optional: initialize eagerly in Application.onCreate; otherwise the first lookup does it
ServiceLoader.initialize()

// home feed screen: the default implementation, or null when it is missing
val home = ServiceLoader.load<HomeService>()
recycler.submitList(home?.recommendations().orEmpty())

// membership UI: same interface, different key
val vip = ServiceLoader.load<HomeService>("vip")

// account center: signed-in implementation, guest variant as the signed-out fallback
val account = ServiceLoader.load<AccountService>() ?: ServiceLoader.load<AccountService>("guest")
```

Module B's code imports only the contract interface. Which module implements it (module A), and
whether that module is even installed, never leaks into the call site.

The full surface is small: `load(service, key)` for one instance, `loadAll(service)` for a
`key -> instance` map, `providers()` / `stream()` to enumerate every entry, `loadClass()` to get
the class without initializing it, and `stateOf()` which answers `READY` / `MISSING` / `DEAD`.
Kotlin also has `ServiceLoader.load<HomeService>()` as shown above.

Instances are not cached: every `load()` / `Provider.get()` constructs the provider again (or
calls its `@ProviderFactory`). Only the configuration table is loaded once and kept. A provider
that must be a single shared object should return that object from its factory (see
"Construction and `@ProviderFactory`" above); `loadClass()` and `stateOf()` never instantiate.

### Dynamic features

The camera page ships in an on-demand module, so the base app cannot compile against
`CameraServiceImpl`. Declare the module in the app (`dynamicFeatures += setOf(":modules:feature_camera")`)
and apply the plugin to it. The implementation opens its own page, so base never learns the
`CameraActivity` class name:

```kotlin
// :modules:feature_camera — the contract call works even when this module is not installed yet
@ServiceProvider(service = CameraService::class)
class CameraServiceImpl : CameraService {
    override fun open(context: Context) {
        context.startActivity(Intent(context, CameraActivity::class.java))
    }
}
```

A camera button then only has to probe:

```kotlin
fun onCameraClick(context: Context) {
    val camera = ServiceLoader.load(CameraService::class.java)
    if (camera != null) {
        camera.open(context)          // installed: the implementation opens its own page
    } else {
        installFeatureCamera()        // request the "feature_camera" split, e.g. via Play Feature Delivery
    }
}
```

Before installation its provider is `MISSING`; after installation the next `load()` returns an
instance. No install listener, no refresh.

### Flavor and build type variants

Each variant gets its own table, so implementations can follow the source sets a real app already
uses. A `free` / `paid` flavor pair can each provide `ChannelService` from `src/free` and
`src/paid`, and a debug-only service under `app/src/debug` simply has no entry in release tables.
A `paid` build only ever sees the `paid` provider. Nothing to wire.

### Several default implementations

The empty key may be shared by more than one implementation. `load()` returns the first available,
ordered by `(service, key, provider FQN)`; `providers()` enumerates all of them. Any non-empty
key stays unique; duplicates fail the build.

## Traditional approaches

Every multi-module project runs into the same wall: business module B needs behavior implemented
in module A, and B must not depend on A. The usual fixes all leave something to carry.

String class names plus reflection is the simplest fix. The caller cannot compile against the
feature, so the class name travels as a string:

```kotlin
val clazz = Class.forName("com.example.feature.home.HomeServiceImpl")
val home = clazz.getDeclaredConstructor().newInstance() as HomeService
```

Renaming the class breaks the string silently, R8 needs `-keep` rules to see the class at all,
and a missing module surfaces as `ClassNotFoundException`.

Most large apps land on a hand-written registry instead. Each feature pushes its implementation
into a shared map:

```kotlin
object ServiceRegistry {
    private val impls = mutableMapOf<Class<*>, Any>()
    fun <T : Any> put(service: Class<T>, impl: T) { impls[service] = impl }
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(service: Class<T>): T? = impls[service] as? T
}

// somewhere in every feature's initializer
ServiceRegistry.put(HomeService::class.java, HomeServiceImpl())
```

No reflection here, but every feature must remember to register, something must trigger those
initializers in the right order, and keyed variants, build types, and dynamic modules all become
hand-written branching.

When the interface already follows the JDK convention, `java.util.ServiceLoader` (or Google's
AutoService) is the obvious pick:

```kotlin
val home = java.util.ServiceLoader.load(HomeService::class.java).first()
```

Under the hood this is reflection over `META-INF/services` strings, so the rename and R8 problems
stay. There is no `key` and no variant awareness, and a provider whose class is missing throws
`ServiceConfigurationError` instead of returning null. AutoService removes the descriptor file at
the cost of APT.

Hilt or Koin solve wiring inside a closed graph, which is the wrong shape for this problem: a
dynamic module that is not installed yet is not a node the graph can compile against, and driving
large builds through KSP/KAPT is the compile-time cost this project avoids.

|  | Reflection | Keyed variants | On-demand modules | Build cost |
|---|---|---|---|---|
| String class names | `Class.forName` | hand-written | hand-written | none |
| Hand-written registry | none | hand-written | install callbacks, manual refresh | none |
| JDK `ServiceLoader` / AutoService | reflective construction | none | throws `ServiceConfigurationError` | APT (AutoService) |
| DI (Hilt / Koin) | none | multibindings | outside the compiled graph | KSP / KAPT (Hilt) |
| **Droid-spi** | none | `key`, per-variant tables | next `load()` returns it | one scan, no APT |

Droid-spi keeps the `ServiceLoader` call shape and moves the table to build time. Unavailable
providers come back as `null`, each variant gets its own table, and an installed dynamic module
becomes visible on the next `load()` without a refresh. In a large modular app this is what lets
teams build against a shared contract: the caller side stays on the interface, the implementation
side stays in its own module, and the build wires the two together. The table references providers
with class literals, so R8 treats them like any other code and needs no extra R8 keep
configuration.

## Project layout

The library itself is three modules:

| Path | Role |
|---|---|
| `plugin/` | Gradle plugin. Scans each variant's sources, merges the manifests, and generates the `ServiceConfiguration` bytecode table. |
| `annotation/` | `@ServiceProvider` and `@ProviderFactory`. CLASS retention; the annotations never enter the runtime. |
| `runtime/` | The `ServiceLoader` facade and its registry. Looks providers up in the generated table, zero reflection. |

Everything else is an integration example: `app/` and `modules/` form a demo with two flavors ×
two build types plus the on-demand camera module, with a self-check screen that verifies the whole
provider table on a device.

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
- Requires JDK 17, AGP 8.0-8.13 or 9.x, `minSdk 24`. AGP 7.x is not supported.
