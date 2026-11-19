# Compile-time service discovery for Android — without reflection or KSP

*Meet droid-spi: a small Gradle plugin that gives Java/Kotlin modules the JDK `ServiceLoader`
experience, keeps working when the provider lives in a dynamic feature module, and adds nothing
to your runtime hot path.*

---

If you maintain a large Android app, you eventually hit one of these walls:

- A base module needs to call into a feature module, but the dependency direction is inverted.
- The implementation lives in an on-demand dynamic feature, so the class literally does not
  exist in the process until the user installs it.
- You want a variant-specific implementation (`free` vs `paid`, `debug` vs `release`) without
  wiring an `if` through half the app.

Classic answers are reflection (`Class.forName` plus a registry), a DI graph, or Google's
AutoService. Each has a cost: reflection is stringly typed and easy to break; DI graphs are
closed at compile time and do not model "this class is not installed yet"; AutoService generates
a `META-INF/services` file that the Android runtime cannot read from an AAR without extra work,
and it has no variant or dynamic-delivery story.

`droid-spi` takes the ServiceLoader idea and moves the provider table to build time.

## What it looks like

Declare an interface in the base module:

```kotlin
interface HomeService {
    fun title(): String
}
```

Declare implementations wherever they live:

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

Initialize once, in `Application.onCreate`:

```kotlin
ServiceLoader.initialize()
```

Look up wherever you need it:

```kotlin
val home = ServiceLoader.load(HomeService::class.java)          // HomeServiceImpl or null
val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // VipHomeServiceImpl or null
val all  = ServiceLoader.loadAll(HomeService::class.java)       // key -> instance
```

That is the whole API surface you need for the common case. Notice what is missing: no
`@Inject`, no component, no code generation plugin, no string class names.

## Why the provider does not have to exist

This is the part that makes it useful for App Bundles.

A dynamic feature module depends on the base app, not the other way around. The base cannot
compile against `CameraServiceImpl` because the class is not on its compile classpath. So how
does base code call it?

`droid-spi` resolves the contradiction at build time:

1. Every module variant scans its own sources and writes a small manifest.
2. The application variant aggregates all compatible manifests and generates one class,
   `ServiceConfiguration`, with a fixed fully-qualified name.
3. That class returns provider classes through **class literals used inside method bodies**.
4. At runtime the JVM resolves those literals lazily. While the feature module is absent, the
   lookup throws `NoClassDefFoundError`; the runtime catches it and reports `MISSING`.
5. Once the module is installed, the next `load()` resolves the literal successfully. There is
   no refresh call and no cached negative result.

The important detail is the absence of a static initializer. If provider class literals were
stored in a `<clinit>`, the JVM would mark the whole configuration class erroneous the first
time an uninstalled module was referenced — permanently breaking every other provider in the
table. The generator deliberately emits no `<clinit>`; every literal sits inside a method body.

## Variants are first-class

`freeDebug` and `paidDebug` compile different source sets. Aggregating "per build type" would
silently give one flavor the other's providers, so aggregation is per **variant**:

- `freeDebug` gets its own scan tasks, its own manifest, and its own generated class.
- The generated class is appended to that variant's `CLASSES` artifact, so packaging picks up
  the right one automatically.
- `paidDebug` is completely independent.

The demo in the repository has two flavors × two build types and a self-check screen that
verifies all 12 invariants on a real device, including "debug-only provider exists only in
debug builds" and "flavor-specific provider differs between free and paid".

## What about third-party `META-INF/services`?

If a library already ships a JDK SPI descriptor, you do not need to fork it. Put the
`META-INF/services/<interface FQN>` file under the module's source resources. The scanner picks
it up and treats each entry like an annotation, except that entries map to the default key and
require a public no-arg constructor. This is how `:modules:core-common` in the demo integrates
a legacy provider.

## Install and try it

```kotlin
plugins {
    id("top.jayvin.droidspi") version "1.0.0-alpha01"
}

dependencies {
    implementation("top.jayvin.droidspi:annotation:1.0.0-alpha01")
    implementation("top.jayvin.droidspi:runtime:1.0.0-alpha01")
}
```

The artifacts are published to GitHub Packages; the repository is
[Garenzhou/android-spi](https://github.com/Garenzhou/android-spi). The README contains the
`pluginManagement` / `dependencyResolutionManagement` snippet, including the token requirement
(GitHub Packages authenticates reads even for public artifacts).

Requirements: JDK 17, AGP 8.x, `minSdk 24`.

## When not to use it

- Your modules are all compile-time dependencies and always installed. Use Hilt/Dagger — you
  get scopes, lifecycle and constructor injection, which this project does not try to provide.
- You need runtime registration of arbitrary classes (plugins loaded from disk). This is a
  compile-time table; every provider must be visible to the build.

If you need "discover a provider that may not be in the process yet" — dynamic features, large
modular apps, variant-specific implementations — that is exactly the gap this fills.

---

*Next: [How droid-spi works internally](02-how-droid-spi-works.md) — artifact transport,
bytecode generation, JVM class-initialization semantics, and the cache/isolation rules that
keep the pipeline correct.*
