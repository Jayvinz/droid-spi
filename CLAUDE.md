# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Prefer English for all code comments, KDoc/Javadoc, README and design documents, and
developer-facing diagnostics (log and exception messages). Use another language only when the
user explicitly asks for it, or for an explicitly maintained translation such as
`docs/droid-spi-design.zh-CN.md`.

## Project overview

**droid-spi** is a **compile-time SPI (ServiceLoader) framework** for Android AAB / multi-module
projects.
Each module applies the Gradle plugin, which scans `@ServiceProvider` annotations and
`META-INF/services/` text files under the module's source directories. The app module
aggregates all manifests into a `ServiceConfiguration` class with a fixed fully-qualified
name (generated as bytecode with ASM). The app instantiates it directly from
`Application.onCreate` via `ServiceLoader.initialize()`.
At runtime the `ServiceLoader` facade resolves providers from this table on demand; once a
module is installed, the next `load()` succeeds without any refresh. Both the configuration
class and providers are referenced through class literals (`new` / `ldc`); there is no
`Class.forName` and no reflective provider construction anywhere.

Design document: `docs/droid-spi-design.md` (Chinese: `docs/droid-spi-design.zh-CN.md`).
User documentation: `README.md` (Chinese: `README.zh-CN.md`)
(terminology mapping, integration steps, known limitations). Long-form articles live in
`docs/articles/` in both languages.

Published coordinates (the brand is `droid-spi`; the Java package is deliberately decoupled
from it and stays `top.jayvin.android.spi`):

- Gradle plugin id: `top.jayvin.droidspi`
- Maven group: `top.jayvin.droidspi`; artifacts `annotation`, `runtime`, `plugin`
- Generated configuration class: `top.jayvin.android.spi.generated.ServiceConfiguration`

## Common commands

```bash
# After modifying the framework modules (annotation / runtime / plugin), always publish to
# local-repo first: the app/modules in the same build consume the plugin from local-repo,
# not as project dependencies.
./gradlew :annotation:publish :runtime:publish :plugin:publish

./gradlew :app:assembleFreeDebug   # one variant (flavors: free / paid; build types: debug / release)
./gradlew :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
./gradlew :app:bundleFreeDebug     # demo AAB (includes the feature_camera split)

# Runtime unit tests (all)
./gradlew :runtime:testDebugUnitTest
# A single test class / a single test method
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest"
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest.missing until installed, load succeeds without refresh"
```

On Windows use `gradlew.bat`. `local-repo/` is gitignored, so **building `:app` right after a
fresh clone fails**; run the publish command first. On a fresh clone (or after deleting
`local-repo/`) that publish needs `--configure-on-demand`, because the demo modules apply the
plugin being published: `./gradlew :annotation:publish :runtime:publish :plugin:publish
--configure-on-demand`. The flag is unnecessary once the plugin exists in `local-repo/`.
`plugin` has no test sources;
the `runtime` tests are plain JVM unit tests that use a fake `ProviderConfiguration` to
simulate "module not installed", so no device or emulator is needed.

Verify the generated artifact (per variant; the path uses the variant name):

```bash
javap -p -c app/build/intermediates/classes/freeDebug/FreeDebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class
```

## Verification checklist

After any change to scanning, aggregation, artifact wiring, or caching, run the full matrix in
`docs/droid-spi-design.md` §8.1. The minimum gate is:

```bash
./gradlew :annotation:publish :runtime:publish :plugin:publish
./gradlew :runtime:testDebugUnitTest --rerun-tasks
./gradlew clean :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
./gradlew :app:assembleFreeDebug --configuration-cache
./gradlew :app:assembleFreeDebug --configuration-cache -Dorg.gradle.unsafe.isolated-projects=true
```

Then add and remove a temporary `@ServiceProvider` once with each cache mode to prove the
scan → generate edges survive, inspect the generated classes and APK contents, and re-run the
four-variant on-device self-check screen.

## CI and release

`.github/workflows/ci.yml` runs on every push and pull request: it publishes the framework to
`local-repo` (with `--configure-on-demand`), runs the runtime unit tests, assembles all four
variants, and repeats the build with configuration cache and isolated projects.

Production publishing is **CI-only**; the build itself has no production repository. A local
`./gradlew publish` writes unsigned artifacts to `local-repo/` and nothing else.

`.github/workflows/release.yml` runs on a `v*` tag. It verifies that the tag equals
`v$spiVersion`, checks the required secrets, and then:

- publishes a signed bundle of `annotation` and `runtime` to **Maven Central** through the
  Central Portal Publisher API (`publishingType=AUTOMATIC`), polling until `PUBLISHED`;
- publishes the Gradle plugin to the **Gradle Plugin Portal** with `:plugin:publishPlugins`;
- attaches the jars/AAR to the GitHub Release.

Required repository secrets: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (a Central Portal user
token), `SIGNING_KEY` (ASCII-armored private key), `SIGNING_PASSWORD`,
`GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET`. The `framework-publishing` convention plugin
enables signing only when `SIGNING_KEY` is present and disables signing tasks otherwise.

To cut a release: bump `spiVersion` in `gradle.properties`, commit, create and push the tag
(`git tag v1.0.0-alpha02 && git push origin v1.0.0-alpha02`).

## Module layout

Single Gradle build; `settings.gradle.kts` includes everything:

| Path | Purpose |
|---|---|
| `annotation/` | Plain Java annotations `@ServiceProvider` / `@ProviderFactory`, zero dependencies, `RetentionPolicy.CLASS` |
| `runtime/` | `ServiceLoader` facade and `ProviderRegistry` on-demand probing (Android Library, no Play Core dependency) |
| `plugin/` | Gradle plugin: `SpiPlugin`, scan tasks, ASM generator, `java-gradle-plugin` |
| `configuration-stub/` | compileOnly placeholder class for runtime (same `ServiceConfiguration` name; not published, not packaged) |
| `buildSrc/` | Convention plugins shared by the framework modules (`framework-publishing`: group, version, local-repo). A precompiled script plugin resolves Gradle API types in the IDE, unlike an applied `gradle/*.gradle.kts` script |
| `app/` | Demo application module; the only module registering the generate task; its Application calls `ServiceLoader.initialize()`. It declares the `channel` flavor dimension (`free` / `paid`) and its self-check screen (`MainActivity`) is the on-device UI test matrix |
| `modules/core-api/` | All service **interfaces** (must live in base) |
| `modules/core-common/` | Resident library example that only has `META-INF/services` configuration |
| `modules/feature-*/` | Provider implementations; `feature_camera` is an on-demand dynamic module |
| `local-repo/` | Publish output of the framework modules (local Maven repository, gitignored) |

## Compile-time pipeline

1. **Per module, per variant** (library / dynamic-feature / application), `SpiPlugin` registers
   `{variantName}ScanProviders` (e.g. `freeDebugScanProviders`). Source directories come from
   `variant.sources` (`java.static`, `kotlin.static`, plus `resources.static` when
   `scanMetaInfServices=true`), so `src/main` **and** the variant's build-type / flavor overlays
   are scanned. Output is `build/generated/spi/{variantName}/services.txt`.
   Manifest format: `service \t provider \t key \t factory` (an empty key means the default variant).
2. **Application modules only** register `{variantName}GenerateServiceConfiguration` per variant.
   It reads the manifests of every compatible module variant, validates `(service, key)`
   uniqueness (a duplicate fails the build), and generates the class with ASM.
3. Manifests travel through Gradle's variant-aware artifact resolution, not a cross-project
   collector. Each module variant publishes its `services.txt` as a `spi-manifest`-typed artifact
   on `{variant}RuntimeElements`; the app's generate task takes an artifact view of
   `variant.runtimeConfiguration` filtered by that type (transitive, and non-plugin modules
   contribute nothing). Dynamic features have no runtime edge into the app, so the app declares
   a dependency for every path in its own `dynamicFeatures` DSL at `projectsEvaluated` and takes
   the same artifact-type-filtered view of their runtime elements; a feature that does not apply
   the plugin simply contributes no manifest. The generate task outputs bytecode via
   `toAppend(ScopedArtifact.CLASSES)` on **its own variant** and does not read the `CLASSES`
   input, so the task graph has no cycle.

**Aggregation is per variant, not per build type**: `freeDebug` and `paidDebug` compile disjoint
flavor sources and get separate tables and separate generated classes. Every variant wires its
own `toAppend`; wiring only the first variant of a build type would silently leave the other
flavors without `ServiceConfiguration` at runtime.

Dynamic feature modules depend on `:app`, so if `:app` declares flavor dimensions they must
declare the same dimensions and flavor names; otherwise Gradle cannot choose a base variant.

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
- The `ServiceLoader` facade never throws: before `initialize()` it logs a warning once, and a
  missing / invalid `ServiceConfiguration` is logged as an error; both degrade to `null` / an
  empty collection.
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
7. **`services()` and `keys()` are index-aligned, one element per entry**: a service with N keys
   appears N times in `services()`. `ProviderRegistry` groups by service and reads keys by index.
   Emitting each distinct interface once breaks startup with
   `Broken provider configuration: services=X keys=Y` (the runtime unit test's fake configuration
   deliberately returns the interface twice per key and documents the expected shape).

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
  local-repo, so the change appears to "do nothing". Forgetting to rebuild the app after
  republishing can also leave stale generated classes in `intermediates/classes` (the generate
  task is UP-TO-DATE while its implementation classpath is not tracked); delete the
  `{Variant}GenerateServiceConfiguration` intermediate directory when debugging stale bytecode.
- Variant matching: a module variant whose flavor dimension is missing from the application
  variant is not aggregated (AGP would not variant-match such a dependency either). Modules
  without flavors match every application flavor. Dynamic features must declare the same flavor
  dimensions as `:app`; a dynamic feature that does not apply the SPI plugin simply contributes
  no providers (its runtime elements carry no `spi-manifest` artifact).
- Manifest transport must stay artifact-based. Do not replace it with a root-project extension or
  a shared build service holding other projects' `Provider<RegularFile>`: under
  configuration-cache reuse, especially with isolated projects, Gradle serializes such
  cross-project providers as plain files, the scan tasks silently leave the task graph, and stale
  manifests reach the generator.
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
