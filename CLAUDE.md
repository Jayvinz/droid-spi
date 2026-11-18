# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Prefer English for all code comments, KDoc/Javadoc, README and design documents, and
developer-facing diagnostics (log and exception messages). Use another language only when the
user explicitly asks for it, or for an explicitly maintained translation such as
`docs/android-spi-design.zh-CN.md`.

## Project overview

A **compile-time SPI (ServiceLoader) framework** for Android AAB / multi-module projects.
Each module applies the Gradle plugin, which scans `@ServiceProvider` annotations and
`META-INF/services/` text files under the module's source directories. The app module
aggregates all manifests into a `ServiceConfiguration` class with a fixed fully-qualified
name (generated as bytecode with ASM). The app instantiates it directly from
`Application.onCreate` via `ServiceLoader.initialize()`.
At runtime the `ServiceLoader` facade resolves providers from this table on demand; once a
module is installed, the next `load()` succeeds without any refresh. Both the configuration
class and providers are referenced through class literals (`new` / `ldc`); there is no
`Class.forName` and no reflective provider construction anywhere.

Design document: `docs/android-spi-design.md` (Chinese: `docs/android-spi-design.zh-CN.md`).
User documentation: `README.md`
(terminology mapping, integration steps, known limitations).

## Common commands

```bash
# After modifying the framework modules (annotation / runtime / plugin), always publish to
# local-repo first: the app/modules in the same build consume the plugin from local-repo,
# not as project dependencies.
./gradlew :annotation:publish :runtime:publish :plugin:publish

./gradlew :app:assembleDebug      # demo APK
./gradlew :app:bundleDebug        # demo AAB (includes the feature_camera split)

# Runtime unit tests (all)
./gradlew :runtime:testDebugUnitTest
# A single test class / a single test method
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest"
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest.missing until installed, load succeeds without refresh"
```

On Windows use `gradlew.bat`. `local-repo/` is gitignored, so **building `:app` right after a
fresh clone fails**; run the publish command above first. `plugin` has no test sources;
the `runtime` tests are plain JVM unit tests that use a fake `ProviderConfiguration` to
simulate "module not installed", so no device or emulator is needed.

Verify the generated artifact:

```bash
javap -p -c app/build/intermediates/classes/debug/DebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class
```

## Module layout

Single Gradle build; `settings.gradle.kts` includes everything:

| Path | Purpose |
|---|---|
| `annotation/` | Plain Java annotations `@ServiceProvider` / `@ProviderFactory`, zero dependencies, `RetentionPolicy.CLASS` |
| `runtime/` | `ServiceLoader` facade and `ProviderRegistry` on-demand probing (Android Library, no Play Core dependency) |
| `plugin/` | Gradle plugin: `SpiPlugin`, scan tasks, ASM generator, `java-gradle-plugin` |
| `configuration-stub/` | compileOnly placeholder class for runtime (same `ServiceConfiguration` name; not published, not packaged) |
| `app/` | Demo application module; the only module registering the generate task; its Application calls `ServiceLoader.initialize()` |
| `modules/core-api/` | All service **interfaces** (must live in base) |
| `modules/core-common/` | Resident library example that only has `META-INF/services` configuration |
| `modules/feature-*/` | Provider implementations; `feature_camera` is an on-demand dynamic module |
| `local-repo/` | Publish output of the framework modules (local Maven repository, gitignored) |

## Compile-time pipeline

1. **Per module** (library / dynamic-feature / application), `SpiPlugin` registers
   `{buildType}ScanProviders` (e.g. `debugScanProviders`). It scans `src/main/java` and
   `src/main/kotlin` (plus `src/main/resources` when `scanMetaInfServices=true`) and writes
   `build/generated/spi/{buildType}/services.txt`.
   Manifest format: `service \t provider \t key \t factory` (an empty key means the default variant).
2. **Application modules only** register `{buildType}GenerateServiceConfiguration`. It reads
   every module's manifest, validates `(service, key)` uniqueness (a duplicate fails the build),
   and generates the class with ASM.
3. Manifests are collected in the root project's `spiRoot` extension; inputs are wired to the
   generate task after `projectsEvaluated`, avoiding configuration-time cross-project evaluation
   order issues. The generate task outputs bytecode via `toAppend(ScopedArtifact.CLASSES)` and
   does not read the `CLASSES` input, so the task graph has no cycle.

**Aggregation is per buildType, not per variant**: one table for `debug` and one for `release`,
shared by flavors. Multi-flavor projects must align per variant.
Scanning only covers `src/main`; flavor-specific directories such as `src/debug/` are not included.

## Runtime path

`ServiceConfiguration` is an ordinary class (no longer a ContentProvider). The app calls
`ServiceLoader.initialize()` from `Application.onCreate`, and that method directly runs
`new top.jayvin.android.spi.generated.ServiceConfiguration` (runtime compiles against the
`configuration-stub` placeholder; at runtime the generated same-name class takes its place).
If the class is missing (app did not apply the plugin), the exception is caught, logged, and
an empty configuration is used.
`ProviderRegistry` does not pre-scan and does not cache `MISSING`:

- `load()` calls `newProvider()` directly; when the class is absent it throws
  `NoClassDefFoundError`, which is caught and turned into `null`, retried on the next call.
  So a newly installed module works on the next `load()`; there is no `refresh()`.
- Only `DEAD` is cached (`<clinit>` failure; the JVM has already marked the class erroneous,
  which is irreversible). `stateOf()` probes on demand and returns `READY` / `MISSING` / `DEAD`.
- `load()` returns `null` when unavailable (it never throws). The default key is the empty
  string (`ServiceLoader.DEFAULT_KEY`); if the default key is absent and the service has
  exactly one available provider, that provider is used as a fallback.
- No Play install listener is registered; `SplitInstallStateUpdatedListener` / `InstallWatcher`
  have been removed.

## Invariants to preserve

These are correctness red lines, not style preferences:

1. **The generated class must never have `<clinit>`**. Provider class literals may only appear
   inside method bodies (`ldc` / `new` / `INVOKESTATIC`). Otherwise, when a dynamic module is not
   installed, the configuration class is marked erroneous by the JVM and the whole table is
   permanently broken. `ConfigurationClassGenerator` deliberately emits no static initializer.
2. **Class literals in `services()` must be interfaces, and the interfaces must live in base /
   resident modules**. If an interface comes from a dynamic module, failing to resolve it inside
   a method body breaks the entire table.
3. `ClassWriter.getCommonSuperClass` is forced to return `java/lang/Object` because implementations
   may come from uninstalled dynamic modules; ASM must not actually load them.
4. A `@ProviderFactory` must be a `@ProviderFactory @JvmStatic fun` inside a `companion object`
   on the Kotlin side, or a `static` method on the Java side, and a class may have only one.
   The scanner enforces the static form and uniqueness; the no-argument and self-return-type
   requirements are not validated, so violations fail at runtime (`NoSuchMethodError`).
5. Kotlin only accepts named arguments for Java annotations:
   `@ServiceProvider(service = X::class)`.
6. The default variant key is the empty string (`ServiceLoader.DEFAULT_KEY == ""`); the key
   parameter is non-null throughout.

## Scanner boundaries (read before changing `SourceScanner`)

- Source code is parsed with regexes plus bracket matching, not an AST. `SourceText`
  strips comments and masks string contents so structure matching cannot be confused.
- `import a.b.*` is only resolvable when the type is imported explicitly or is unique in the
  module's `TypeIndex`; otherwise the build fails and asks for an explicit import / FQN.
- Nested classes and `typealias` are not supported as services.
- Provider names in `META-INF/services` files must look like `com.foo.BarImpl` (dot-separated
  FQN), otherwise the build fails.
- An annotation and an SPI config entry pointing at the same implementation are deduplicated
  by identical line; different implementations competing for the same `(service, key)` fail
  the build.

## Known pitfalls

- Changing plugin source without republishing: the demo still consumes the old version from
  local-repo, so the change appears to "do nothing".
- `ServiceLoader.initialize()` directly runs `new` on the fixed class name
  `top.jayvin.android.spi.generated.ServiceConfiguration`; runtime compiles against the
  `configuration-stub` compileOnly placeholder, and the placeholder is never packaged.
  If the class is missing, it only logs and degrades to an empty configuration.
- R8: base reports missing classes for uninstalled modules; add `-dontwarn` per module
  (see `app/proguard-rules.pro`).
- Environment: JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35;
  `local.properties` contains the local SDK path.
- Gradle daemon JVM is pinned by `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=17`);
  change it with `./gradlew updateDaemonJvm --jvm-version=<version>`, do not edit that file by hand.
