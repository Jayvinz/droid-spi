# Android service discovery for code that may not be installed yet

*Droid-spi moves the JDK ServiceLoader table to build time, so the base module can call implementations in dynamic features and flavor overlays without reflection.*

## The dependency that points the wrong way

In a multi-module Android app, feature modules depend on the base module. That direction works until the base needs a feature back. A home screen or a settings list lives in base, while the implementation lives in `feature-home` or `feature-account`. Base cannot compile against those classes, and adding the reverse dependency would collapse the module boundary.

Dynamic delivery makes it worse. `feature_camera` may not be downloaded when the app starts. The class does not exist in the process, and no dependency graph can make it appear early.

Flavors add a third case. `free` and `paid` compile different sources for the same interface. Base needs the implementation for the current variant, not whichever one happened to be linked first.

The usual workarounds all have costs:

| Approach | What gets in the way |
|---|---|
| `Class.forName` and a string registry | Strings instead of types; refactors fail at runtime; R8 cannot see the target |
| AutoService or annotation processing | `META-INF/services` is awkward to consume from an AAR or APK, processors slow down large builds, and there is no variant or dynamic delivery model |
| Hilt or Dagger | The graph closes at compile time, so it cannot represent a class that is not installed |
| Routing libraries | APT tables and runtime scans; dynamic modules are often manual |

Droid-spi covers the case those tools leave open: a provider that is known to the build but not guaranteed to be present in the process.

## What the framework does

The vocabulary follows `java.util.ServiceLoader`. A service is an interface in the base or a resident module. A provider is an implementation annotated with `@ServiceProvider`. At build time, every module variant scans its sources and writes a small manifest. The app variant merges the manifests into one generated class with a fixed name:

`top.jayvin.android.spi.generated.ServiceConfiguration`

The class contains a class literal for each provider, and those literals sit inside method bodies. At runtime the facade resolves one literal per lookup. If the dynamic module is not installed, the entry is unavailable. After installation, the next `load()` succeeds. The design avoids `Class.forName`, reflective construction, and annotation processors.

## Setup

The plugin is on the Gradle Plugin Portal and the libraries are on Maven Central, so ordinary repositories are enough:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Apply the plugin to every module that declares providers, including the app module. The app also needs the runtime:

```kotlin
plugins {
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    // application module only
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

Supported module types are application, library, and dynamic-feature. The current requirements are JDK 17, AGP 8.0 through 8.13 or 9.x, and `minSdk 24`.

## Declaring a service and its providers

Put the interface in a module everyone can see:

```kotlin
interface HomeService {
    fun title(): String
    fun recommendations(): List<String>
}
```

Implement it wherever the code belongs:

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService {
    override fun title() = "Home"
    override fun recommendations() = listOf("Popular", "New")
}
```

The same interface can have labeled implementations:

```kotlin
@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    override fun title() = "Home, VIP variant"
    override fun recommendations() = listOf("Dedicated support", "Free shipping")
}
```

Kotlin requires named arguments for Java annotations, hence `service = HomeService::class`. In Java, write `@ServiceProvider(HomeService.class)` or `@ServiceProvider(service = HomeService.class, key = "vip")`.

One rule needs code review because the scanner cannot check it: the service interface must live in base or a resident library. If the interface sits inside a dynamic module, the generated table cannot resolve it before installation, and lookups for every service are affected.

## Lookup

Initialization is optional. The configuration loads on first use, so calling `initialize()` in `Application.onCreate` only moves the cost to startup and surfaces a missing generated class early:

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
```

Queries:

```kotlin
val home = ServiceLoader.load(HomeService::class.java)          // default key, may be null
val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // labeled variant, may be null
val all  = ServiceLoader.loadAll(HomeService::class.java)       // key to instance
val state = ServiceLoader.stateOf(HomeService::class.java)      // READY, MISSING, DEAD, or null
```

`providers()` and `stream()` return handles without constructing the provider. `loadClass()` resolves the class without triggering its static initialization. `snapshot()` returns the whole table as `(service, key, state)`, which is useful when a lookup returns `null` and you want to know why.

Lookups do not throw. An unavailable provider produces `null` or an empty collection. An app that never applied the plugin gets an empty configuration and a log line, not a crash. For a discovery layer called during startup, that behavior is deliberate.

Instances are not cached. Every `load()` constructs a new provider or calls its factory, which matches `java.util.ServiceLoader` semantics. If you need one shared instance, return it from a factory.

## Constructors, singletons, and @ProviderFactory

By default the generated bytecode calls `new Impl()`. If the constructor is private, takes arguments, or should return an existing object, annotate a static no-argument factory:

```kotlin
@ServiceProvider(service = AccountService::class)
class AccountServiceImpl private constructor(private val suffix: String) : AccountService {

    override fun userName() = "garen$suffix"
    override fun level() = "Lv.3"

    companion object {
        private val shared = AccountServiceImpl("")

        @ProviderFactory
        @JvmStatic
        fun create(): AccountServiceImpl = shared
    }
}
```

The generator emits `INVOKESTATIC AccountServiceImpl.create()LAccountServiceImpl;`, so the factory path is still reflection-free. The method must return the provider class itself, not the interface. Kotlin needs a `companion object` with `@JvmStatic`; Java needs a `static` method. A class may have one factory. The scanner checks the static form and uniqueness, though it does not validate the signature, so a wrong parameter list fails with `NoSuchMethodError` on the first load.

## Dynamic features

This is the case the design is built around.

```kotlin
val camera = ServiceLoader.load(CameraService::class.java)
if (camera == null) {
    // state is MISSING until the split is installed
    installManager.startInstall(
        SplitInstallRequest.newBuilder()
            .addModule("feature_camera")
            .build()
    )
}
```

After the module is installed, the next `load()` returns an instance. There is no `refresh()` and no install listener inside the framework. The registry probes the class literal again on each call. A missing class is not cached because it can become present. A failed class initialization is cached because the JVM has marked the class erroneous, and retrying cannot help.

The framework does not download anything. Install policy stays with your app, whether it uses Play Feature Delivery or another channel. Once the class is visible to the process, discovery works.

## Variant-specific providers

Aggregation happens per variant, not per build type. `freeDebug` and `paidDebug` compile different sources and receive different tables.

```
modules/feature-home/
  src/main/kotlin/.../HomeServiceImpl.kt
  src/free/kotlin/.../FreeChannelServiceImpl.kt
  src/paid/kotlin/.../PaidChannelServiceImpl.kt
```

Apply the plugin in the module, declare the same flavor dimensions and flavor names as the app, and the scan picks up the overlay for the requested variant:

```kotlin
@ServiceProvider(service = ChannelService::class)
class PaidChannelServiceImpl : ChannelService {
    override fun channelName() = "paid"
}
```

A provider in `src/debug` appears only in debug builds. Modules without flavors match every app flavor. If a module declares a dimension the app does not have, that module variant is not aggregated, which matches how AGP would refuse to match the dependency in the first place.

Dynamic features must declare the same flavor dimensions as the app. Otherwise Gradle cannot choose a base variant for them.

## Existing META-INF/services files

If a library already ships standard SPI descriptors, copy the text file into your module's source resources:

```
src/main/resources/META-INF/services/com.example.core.api.LegacyService
```

```
com.example.core.common.LegacyServiceImpl
```

The scanner reads source resources only, not dependency artifacts, so the build does not unpack hundreds of AARs. Each entry maps to the default key and, without a factory, needs a public no-argument constructor.

## R8 and debugging

Provider classes are referenced by `ldc` constants in generated bytecode, so R8 sees a live reference once the class is present. For classes in a dynamic module that is not installed, the base build reports them as missing. Add a rule per module:

```
-dontwarn com.example.feature.camera.**
```

When a lookup returns `null` and you expect an instance, call `ServiceLoader.snapshot()` and read the state. `MISSING` usually means the module is not installed or the interface lives somewhere the table cannot reach. `DEAD` means the provider's static initialization failed once and will not be retried.

## Limits

Droid-spi is a compile-time table. Every provider must be visible to the build. Loading arbitrary classes from disk at runtime is outside its scope.

Interfaces cannot be nested types or `typealias`es. Explicit keys must be unique per service, and two providers competing for one non-empty key fail the build. The empty default key may have several implementations; `load()` returns the first available one, while `providers()` enumerates all of them.

There is no scope, lifecycle, or constructor injection. If your whole dependency graph is installed for the life of the process, Hilt or Dagger will give you more. This framework covers the part of the graph that may not be there.

## Where to find it

```kotlin
dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

Repository: https://github.com/Jayvinz/droid-spi

Design document: [docs/droid-spi-design.md](../droid-spi-design.md)

A companion article covers the build pipeline: how manifests travel as typed Gradle artifacts, what the generated bytecode looks like, and why the configuration class must not have a static initializer.
