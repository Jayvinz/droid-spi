# Android SPI ServiceLoader

A **compile-time provider configuration table** for Android AAB multi-module projects.
The naming follows the SPI vocabulary of `java.util.ServiceLoader`
(service / provider / provider-configuration). It uses no APT/KSP and never unzips
third-party jars, yet providers living in dynamic feature modules are available at runtime;
once a module is installed, the next `load()` simply works (on-demand probing, no refresh).
Providers can be declared with the `@ServiceProvider` annotation or through standard
`META-INF/services/` text files in source directories (for integrating implementations
shipped by third-party jars / aars under the JDK SPI convention).

Runtime compiles a direct `new` against the generated class by using a compileOnly
placeholder class; at runtime the app plugin's generated same-name class takes its place.
Providers are referenced through class literals too, so there is no `Class.forName` anywhere.

> For the full design background, JVM semantics and comparison with other solutions, see
> [docs/android-spi-design.md](docs/android-spi-design.md).

```
Scan (per module)                              Aggregate (app)                 Runtime
@ServiceProvider ─┐                                                        Application.onCreate
                  ├─scan─▶ manifest ─merge─▶ ServiceConfiguration.class ─dex─▶   └─▶ initialize()
META-INF/services ─┘                                                            (new ServiceConfiguration)
                                                                           after install, load() just works
```

## Terminology mapping with JDK SPI

| JDK `java.util.ServiceLoader` | This project | Notes |
|---|---|---|
| service | `HomeService` interface | Contract living in base |
| service provider | `HomeServiceImpl` | Implementation in each feature module |
| provider-configuration file | `ServiceConfiguration` (primary) / `META-INF/services/` in source dirs (compatible) | Generated class is primary; standard SPI text is a supplementary input |
| `ServiceLoader.load(service)` | `ServiceLoader.load(service, key)` | Adds a business variant key |
| `ServiceLoader.Provider.type()/get()` | `ServiceLoader.Provider.type()/get()/state()` | Adds provider state |
| `ServiceLoader.stream()` | `ServiceLoader.stream()` | Same semantics; returns `Sequence` on Kotlin |

Main differences: `load()` returns an instance directly instead of a loader object;
unavailable services return `null` instead of throwing; an extra `key` and `ProviderState`
model the intermediate states of dynamic delivery.

## Layout

```
android-spi/                       Single Gradle build; all modules included in settings.gradle.kts
├── annotation/                     Framework: @ServiceProvider / @ProviderFactory (plain Java annotations, zero dependencies)
├── runtime/                        Framework: ServiceLoader facade + on-demand provider probing (Android Library)
├── plugin/                         Framework: scanning and generation (Gradle Plugin, ASM bytecode)
├── configuration-stub/            compileOnly placeholder for runtime (never packaged into APK/AAR)
├── app/                           Demo main module (consumer): Application calls initialize()
├── modules/                       Demo non-main modules live here
│   ├── core-api/                  Service interface layer (every @ServiceProvider service lives here)
│   ├── core-common/               Resident library (contains the META-INF/services example)
│   ├── feature-home/              Default HomeService provider + vip variant
│   ├── feature-account/           @ProviderFactory private constructor + guest variant
│   └── feature_camera/            on-demand dynamic module: CameraService provider + standalone Activity
├── docs/                          Full design document
└── local-repo/                    Publish output (local Maven repository, gitignored)
```

Gradle module paths map one-to-one to directories: `:app`, `:modules:core-api`,
`:modules:feature_camera`, `:plugin`, etc.

## Quick start

```bash
# 1. Publish the three framework modules to the local Maven repo (local-repo/);
#    demo modules consume the plugin from the same repository
./gradlew :annotation:publish :runtime:publish :plugin:publish

# 2. Build the demo (in the same build)
./gradlew :app:assembleDebug      # APK
./gradlew :app:bundleDebug        # AAB (with the feature_camera split)
```

Run tests:

```bash
./gradlew :runtime:testDebugUnitTest
```

Inspect the artifacts:

```bash
# Configuration bytecode: implementation classes are only referenced inside method bodies, no <clinit>
javap -p -c app/build/intermediates/classes/debug/DebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class

# Confirm providers are in the APK and the configuration class is in the base dex
"D:/Android/Sdk/build-tools/35.0.0/aapt2.exe" dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
```

> The configuration class no longer appears in the manifest; the manifest only needs the
> Application that calls `ServiceLoader.initialize()`.

## Integration in three steps

**1. Dependencies and plugin**

```kotlin
plugins {
    id("com.android.application")     // same for library / dynamic-feature
    id("org.jetbrains.kotlin.android")
    id("top.jayvin.android.spi")
}

dependencies {
    implementation("top.jayvin.android.spi:annotation:1.0.0")
    implementation("top.jayvin.android.spi:runtime:1.0.0")
}
```

**2. Initialize in Application.onCreate (the only handwritten glue)**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()   // directly instantiate the generated configuration class
    }
}
```

```xml
<application android:name=".MyApp">
```

`initialize()` directly runs `new` on the fixed class name
`top.jayvin.android.spi.generated.ServiceConfiguration`. If the class does not exist
(the app did not apply the plugin), the exception is caught and logged, after which the
facade APIs return null / empty collections; it never crashes. Calling it from
`Application.onCreate` guarantees the table is ready before any Activity starts.

**3. Declare and call**

The annotations are **plain Java annotations**, so both pure Java and Kotlin projects can
use them. Kotlin only accepts **named arguments** for Java annotations, so `service =`
is mandatory:

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService

@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    // For constructors with parameters or private constructors, use a static factory
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

In pure Java projects, both positional and named arguments work:

```java
@ServiceProvider(HomeService.class)
public class HomeServiceImpl implements HomeService { }

@ServiceProvider(service = HomeService.class, key = "vip")
public class VipHomeServiceImpl implements HomeService { }
```

Omitting `key` (the annotation default, an empty string) means the default variant;
`ServiceLoader.DEFAULT_KEY` is also the empty string at runtime.

```kotlin
val home = ServiceLoader.load<HomeService>()                    // Kotlin-only reified form
val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // key variant
val cls  = ServiceLoader.loadClass(HomeService::class.java)     // class only
val all  = ServiceLoader.loadAll(AccountService::class.java)    // key -> instance

// JDK-style Provider handles
val first = ServiceLoader.findFirst(HomeService::class.java)
ServiceLoader.stream(HomeService::class.java).forEach { provider ->
    Log.d("spi", "${provider.type().name} / ${provider.state()}")
}
```

Every public method is `@JvmStatic` for Java callers, but Java has no reified generics,
so pass Class literals; `Sequence` is awkward in Java, so use `providers()` instead:

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

All `load*` methods return `null` instead of throwing: an unavailable service
(a dynamic module that is not installed) is a normal state, not an error.

## Compatible with standard SPI configuration (META-INF/services)

Implementations inside third-party jars / aars usually do not carry our
`@ServiceProvider` annotation, but they often declare
`META-INF/services/<service FQN>` following the JDK SPI convention. To avoid modifying
third-party source code, the framework **reads a same-named text file under source
directories** as an equivalent input:

```
your-module/src/main/resources/META-INF/services/com.example.core.api.LegacyService
```

The file content is exactly what `java.util.ServiceLoader` expects: one implementation
fully-qualified name per line, `#` starts a comment, blank lines are ignored:

```text
# Simulates a third-party library declaring a provider through JDK SPI
com.example.core.common.LegacyServiceImpl
com.thirdparty.SomeOtherImpl   # multiple entries are supported too
```

Key points:

- **Configuration is maintained in this project's source; the implementation classes are
  provided by the third-party jar / aar**. The framework only scans text files in source
  directories and **does not unzip or scan classes in dependency artifacts** (preserving the
  "no third-party jar scanning" constraint);
- Scan path: `src/main/resources/META-INF/services/**`, and also any `META-INF/services/`
  directory at any depth under `src/main/java` / `src/main/kotlin`; controlled by
  `spi { scanMetaInfServices = true }` (enabled by default);
- Standard SPI configuration has no `key` / `factory` concept, so entries map to
  `key = ""` (default variant) and no-arg construction; therefore **implementations must
  have a public no-arg constructor**. For key variants or private constructors + factories,
  keep using `@ServiceProvider` / `@ProviderFactory`;
- Every entry goes through exactly the same aggregation and bytecode generation as
  annotation declarations; runtime behavior is identical;
- When both declarations point at the **same implementation class**, the identical line is
  deduplicated rather than treated as a conflict; different implementations competing for the
  same `(service, key="")` still fail the build.

> That module must apply the `top.jayvin.android.spi` plugin, otherwise there is no
> `xxScanProviders` task and the configuration is not scanned.
> `modules/core-common` is the minimal example that only has SPI configuration and no annotations.

## What happens after a dynamic module is installed

The framework performs no pre-scan and caches no `MISSING` state. Provider class constants
inside `providerOf` / `newProvider` are resolved only when the corresponding branch executes,
so after a module is installed:

1. Call Play's `startInstall()` anywhere in the process;
2. Once installation completes, the dynamic module's classes become visible to the process
   (guaranteed by the host's install channel; the framework does not intervene);
3. The next `ServiceLoader.load()` probes on demand and gets an instance directly.

There is no `refresh()` and no `MISSING` table, so there is no window where "it is installed
but the table is stale". The only remembered state is `DEAD`: once a provider's `<clinit>`
throws, the JVM has marked it erroneous and it is not retried in this process.

Cold start (module installed earlier) and process restart (same) need no extra handling.
While a module is not installed, every `load()` retries and returns `null`.

## Class references and missing-class handling

- **Configuration class**: runtime depends on the `configuration-stub` module through
  `compileOnly`, which lets it compile a direct `new ServiceConfiguration()` call. At runtime
  the same-name class generated by the app plugin takes its place; the placeholder is never
  packaged into the APK / AAR.
- **Providers are referenced through class literals**: generated bytecode uses `ldc` /
  `new` / `INVOKESTATIC` on `com/example/...Impl`, so R8 sees the references and keeps the
  classes automatically; there is no reflection and no `Method.invoke` at runtime.
- **Critical constraint**: those literals **may only appear inside method bodies, never in
  `<clinit>`**. Otherwise, while a dynamic module is not installed, the configuration class
  itself is marked erroneous (`Could not initialize class`) and the whole table is broken
  beyond recovery. The generator deliberately emits no `<clinit>`.
- **When the configuration class is missing** (the app did not apply the plugin):
  `initialize()` catches `NoClassDefFoundError`, logs it, and switches to an empty
  configuration, so `load()` returns null and `stream()` / `snapshot()` return empty
  without crashing.
- R8 sees the `new` reference and keeps the class when it exists; `runtime/consumer-rules.pro`
  adds `-dontwarn` for the missing case.

## What the generated class looks like

```java
public final class ServiceConfiguration implements ProviderConfiguration {
    public ServiceConfiguration() { super(); }

    public Class[] services() { return new Class[]{ AccountService.class, CameraService.class, HomeService.class }; }
    public String[] keys()    { return new String[]{ "", "guest", "", "", "vip" }; }

    public Class providerOf(Class service, String key) {
        if (service == HomeService.class) {
            if ("".equals(key))    return HomeServiceImpl.class;
            if ("vip".equals(key)) return VipHomeServiceImpl.class;
            return null;
        }
        ...
    }

    public Object newProvider(Class service, String key) {
        if (service == AccountService.class) {
            if ("".equals(key)) return AccountServiceImpl.create();   // INVOKESTATIC
        }
        ...
    }
}
```

`services()` contains interfaces only. Interfaces must live in base; otherwise a failed
resolution inside a method body breaks the whole table (the only hard constraint).

## Build-time validation

The scanner prefers a failed build over generating a wrong table:

| Violation | Result |
|---|---|
| Two providers for the same `(service, key)` | `GenerateServiceConfigurationTask` fails and lists both implementations |
| `@ProviderFactory` non-static / missing `@JvmStatic` | `ScanProvidersTask` fails with the correct form |
| More than one `@ProviderFactory` in a class | Fails |
| `service` is not a valid expression (not `X::class` / `X.class`) | Fails |
| `import a.b.*` present and the FQN cannot be determined | Fails; asks for an explicit import or a fully-qualified name |
| Invalid provider name in a `META-INF/services/` file (not `com.foo.Bar` form) | `ScanProvidersTask` fails with the file name and line content |
| An annotation and an SPI entry use different implementations for the same `(service, key)` | `GenerateServiceConfigurationTask` fails and lists both implementations |

## Known limitations

- **Interfaces must live in base**: if a class literal inside `services()` comes from a
  dynamic module, the whole table breaks.
- **Scans `src/main/java` + `src/main/kotlin` + `src/main/resources` (SPI config)**:
  flavor-only directories (`src/debug/`) and custom source sets are not covered;
  `META-INF/services` is only read from source directories, not from dependency artifacts.
- **Aggregation is per buildType**: one table for `debug`/`release`, shared by flavors;
  multi-flavor projects need per-variant alignment.
- **Nested classes / `typealias` are not supported as services**: the scanner resolves
  top-level types.
- **Non-Play channels**: the framework does not depend on Play; the install channel and
  class visibility of dynamic delivery are the host's responsibility. Providers packaged
  into the APK still work normally.
- **R8**: base reports missing classes for uninstalled modules; add `-dontwarn` per module
  (see the example in `app/proguard-rules.pro`).

## Environment

- JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35
- Gradle Daemon JVM is pinned to 17 by `gradle/gradle-daemon-jvm.properties`;
  change it with `./gradlew updateDaemonJvm --jvm-version=<version>`
- In regions where dependency downloads benefit from a proxy, uncomment the clash proxy
  section in `gradle.properties` and adjust the port; it is commented out by default and
  does not affect other environments
- The Gradle Wrapper uses the official distribution URL; the first build downloads Gradle 8.9
