# Droid-spi · Design

> A compile-time provider configuration table for AAB multi-module projects. Naming follows
> the SPI vocabulary of `java.util.ServiceLoader`.
> Implementation: the `Droid-spi` repository (`app/` + `modules/` are the
> demo, `annotation` / `runtime` / `plugin` are the framework, all in a single build).
> Core traits: **zero reflection — `Class.forName` is never used and providers are constructed
> through a direct `new` / `INVOKESTATIC`, never reflective constructor or method calls; zero
> APT/KSP; zero third-party jar scanning; metadata via class literals; providers probed on
> demand**.
>
> 中文版：[droid-spi-design.zh-CN.md](droid-spi-design.zh-CN.md)

---

## Contents

1. [Goals and constraints](#1-goals-and-constraints)
2. [Overall architecture](#2-overall-architecture)
3. [Compile time](#3-compile-time)
4. [Runtime](#4-runtime)
5. [Key JVM semantics](#5-key-jvm-semantics-whether-the-design-holds)
6. [Comparison with mainstream solutions](#6-comparison-with-mainstream-solutions)
7. [Boundaries and known risks](#7-boundaries-and-known-risks)
8. [Verification](#8-verification-executed-locally)

---

## 1. Goals and constraints

### 1.1 The problem

AAB dependency direction is one-way: dynamic feature modules depend on base, and base
**cannot see** dynamic module classes at compile time. Yet business code in base needs to call
services provided by dynamic modules, and those modules may not be installed at all.

This framework provides:

1. Base code writes `ServiceLoader.load(HomeService::class.java, "vip")` and gets a provider;
2. Providers may live in a resident library or in an on-demand dynamic module;
3. After a module is installed, the next `load()` probes on demand and succeeds; `load()`
   changes from returning null to returning an instance.

### 1.2 Hard constraints (from real project decisions)

| ID | Constraint | Reason |
|---|---|---|
| C1 | **No reflection: no `Class.forName`, and no reflective provider construction or method calls** | Reflection bypasses R8 visibility and throws `Error` that is hard to handle. The configuration class compiles a direct `new` against a compileOnly placeholder; providers are constructed with a direct `new` (`INVOKESPECIAL <init>`) or a static factory (`INVOKESTATIC`) |
| C2 | **No APT / KSP** | Processors slow down incremental builds in large projects |
| C3 | **No third-party jar scanning** | Read only our own sources; avoid unzipping and reassembling hundreds of MB of AARs |
| C4 | **Prefer class literals for metadata** | String class names are invisible to R8, unsafe under refactoring, and misspellings only surface at runtime |
| C5 | **Classes may only become visible after a dynamic module is installed** | The core scenario of Play Feature Delivery |
| C6 | **No compound API such as `loadOrInstall`** | Installation triggering belongs to the host or Play's own flow; the framework only guarantees "the next load after install succeeds" |

`key` is the only remaining string dimension: it is a "business variant label for the same
interface" (`""` / `"vip"` / `"guest"`), not a class name, and the type system cannot express it.

> C3's "no third-party jar scanning" does not mean "no third-party providers". The approach is:
> keep the third-party implementation class in its jar / aar, but copy the
> `META-INF/services/<service>` text it declares under the JDK SPI convention into this
> project's source directory (`src/main/resources/META-INF/services/`) — we only read our own
> text and never touch dependency artifacts (see 3.3).

### 1.3 Vocabulary mapping with standard SPI

The naming deliberately mirrors JDK SPI for easy migration and understanding:

| JDK `java.util.ServiceLoader` | This project | Notes |
|---|---|---|
| service | `HomeService` interface | Contract pulled down into base |
| service provider | `HomeServiceImpl` | Implementation in each feature module |
| provider-configuration file | `ServiceConfiguration` (primary) / source-dir `META-INF/services/` (compatible) | Generated class is primary; standard SPI text is a supplementary input |
| `ServiceLoader.load(service)` | `ServiceLoader.load(service, key)` | Adds a business variant key |
| `ServiceLoader.Provider.type()/get()` | `ServiceLoader.Provider.type()/get()/state()` | Adds provider state |
| `ServiceLoader.stream()` | `ServiceLoader.stream()` / `providers()` | Same semantics; Kotlin returns `Sequence`, Java uses `providers()` returning `List` |

Differences: `load()` returns an instance directly instead of a loader object; unavailable
services return `null`; an extra `key` and `ProviderState` are present.

---

## 2. Overall architecture

```
┌─ Scan (per module variant) ──────────┐   ┌─ Aggregate (app, per variant) ──┐   ┌─ Runtime ──────────────┐
│ sources = src/main + flavor         │   │ compatible module manifests    │   │ Application.onCreate  │
│ / build-type overlays               │   │ ↓ merge + conflict check       │   │ ↓ initialize()        │
│ (*.kt | *.java)                     │   │ ServiceConfiguration.class     │   │ ↓ load() → READY      │
│ META-INF/services/**                │   │ ↓ toAppend(CLASSES)            │──▶│ ↓ INSTALLED           │
│ ↓ lexical scan                      │   │ base dex → APK/AAB             │   │ next load() → READY   │
│ services.txt                        │   └───────────────────────────────┘   └──────────────────────┘
│ service/provider/key/factory        │
└────────────────────────────────────┘
```

The three responsibilities are cleanly separated:

- **Scan**: reads only source text (annotation sources + standard SPI config text), does not
  depend on compiled artifacts and does not unzip dependency artifacts, fully decoupled from
  Kotlin/Java compile order;
- **Aggregate**: runs in the app module; input is plain-text manifests, output is one class;
- **Runtime**: state query + on-demand probing; there is no "find a class by name" action.

---

## 3. Compile time

### 3.1 Annotations

The `annotation` module is a **plain Java module** (`java-library`) so pure Java projects can
reference it directly:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ServiceProvider {
    Class<?> service();
    String key() default "";
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ProviderFactory { }
```

Retention is `CLASS` — scanning happens at the source stage; the annotations never enter
runtime and never need reflection.

Kotlin **only accepts named arguments** for Java annotations, so Kotlin must write `service =`:

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService
```

Java accepts both positional and named arguments:

```java
@ServiceProvider(HomeService.class)
public class HomeServiceImpl implements HomeService { }
```

The scanner supports both forms (`X::class` / `X::class.java` / `X.class`, with or without
the `service =` prefix) and also accepts a positional key after the service:
`@ServiceProvider(HomeService.class, "vip")`.

**Three requirements for `@ProviderFactory`**:

1. Must be a static method (Kotlin needs `@JvmStatic`, Java needs `static`);
2. Must take no arguments;
3. The return type must be the enclosing class itself.

The scanner enforces requirement 1 and allows at most one `@ProviderFactory` per class.
Requirements 2 and 3 are not validated at build time: the generated
`INVOKESTATIC provider.factory()L<provider>;` descriptor is assembled statically, so a
violation produces a descriptor mismatch that fails when the method is resolved at runtime
(see the risk table in section 7).

The third requirement also lets the descriptor be assembled statically. Loosening it to a
supertype would require resolving the return type FQN: little benefit, large error surface.

### 3.2 Scanner

It does not use bare regexes but two levels of processing — **comment stripping + string
masking** — both of which **preserve character offsets**:

| Processing | Effect | Purpose |
|---|---|---|
| `stripComments` | Replaces comments with spaces, keeps strings intact | Read values such as `key = "vip"` |
| `maskStrings` | Replaces string contents (including quotes) with spaces | Structural scanning, so `class` / `@ServiceProvider` inside strings cannot cause false hits |

Flow:

```
locate @ServiceProvider → match parentheses → split top-level commas
   ├─ first argument: X::class / X::class.java / X.class → service type name
   └─ rest: key = "..." or a positional "..."
→ find the class declaration after the closing paren (skipping strings) → provider FQN
→ find @ProviderFactory inside the class body → factory method name
```

**Type resolution**:

1. Explicit `import a.b.C` → `a.b.C` (including `as` aliases);
2. Same package (the type is actually declared in this module's TypeIndex);
3. The only type with that simple name in this module;
4. Otherwise, if `import a.b.*` exists → **fail the build** (cannot determine the FQN);
5. Otherwise treat it as same-package.

Step 4 prefers failure over guessing: a wrong FQN is not exposed at build time and only becomes
a `NoClassDefFoundError` at runtime.

### 3.3 Standard SPI configuration compatibility (META-INF/services)

Third-party jar / aar implementation classes usually do not carry `@ServiceProvider`, but most
declare `META-INF/services/<service FQN>` following the JDK SPI convention. To integrate such
providers, the framework additionally scans same-named text files **in source directories**:

```
your-module/src/main/resources/META-INF/services/com.example.core.api.LegacyService
```

The parser (`ServiceConfigScanner`) reads the text and takes one provider FQN per line; `#`
starts a comment and blank lines are ignored. The file name is the service FQN (the part of
the path after `META-INF/services/`). Constraints and mapping:

| Item | Handling |
|---|---|
| Scan directories | Every static source directory of the variant (`variant.sources.java` / `kotlin` / `resources`), i.e. `src/main` plus build-type and flavor overlays; `META-INF/services/` is accepted at any depth under them |
| Switch | `spi { scanMetaInfServices = true }`, enabled by default |
| Scope | Reads only these text files; **does not unzip jars / aars, does not scan classes**, preserving C3 |
| `key` | Standard SPI has no such concept → `""` (default variant) |
| Instantiation | No factory → no-arg constructor; **the implementation must have a public no-arg constructor** |
| Dedup | Same provider declared by an annotation and by SPI config (identical line) is deduplicated; different implementations competing for the same **non-empty** key fail in the aggregation task (the empty key may have several implementations) |
| Validity | A provider name not matching `^[\w.$]+$` fails the build with the file name and line content |

Each config entry maps to `ProviderEntry(service, provider, "", "")` and then follows exactly
the same path as annotation entries: module manifest → aggregation validation → generated
`providerAt` / `newProviderAt`. Runtime behavior is identical.

Prerequisite: the module must apply the `top.jayvin.droidspi` plugin (otherwise there is no
`{variantName}ScanProviders` task). `modules/core-common` is the minimal example with SPI config only and
no annotations — its manifest output is:

```
com.example.core.api.LegacyService  com.example.core.common.LegacyServiceImpl
```

### 3.4 Manifests and aggregation

Each module has one `ScanProvidersTask` per **variant** (`freeDebugScanProviders`,
`paidReleaseScanProviders`, …), producing a deterministic manifest (sorted, no timestamps):

```
com.example.core.api.AccountService	com.example.feature.account.AccountServiceImpl		create
com.example.core.api.AccountService	com.example.feature.account.GuestAccountServiceImpl	guest
```

Four columns: `service \t provider \t key \t factory` (an empty `key` means the default
variant; an empty `factory` means no-arg construction).

Manifests reach the app through Gradle's variant-aware resolution, not through a cross-project
collector:

- every plugin module variant adds its manifest to `{variant}RuntimeElements` as an artifact of
  the custom type `spi-manifest`. The app's generate task takes an artifact view of
  `variant.runtimeConfiguration` filtered by that type, so it sees the manifests of all
  (transitive) runtime dependencies that apply the plugin; modules without the plugin simply
  contribute no manifest;
- dynamic features are not on the app's runtime classpath. The app declares a dependency for each
  project in its own `dynamicFeatures` DSL and takes the same artifact-type-filtered view of
  their runtime elements. A feature that does not apply the plugin carries no manifest artifact
  and simply contributes no providers — there is no requirement to apply the plugin to features
  that do not declare any.

`GenerateServiceConfigurationTask` runs per application variant, validates that every **non-empty**
key is unique per service (a conflict fails the build and lists both providers; the empty key may
have several implementations), then generates the `ServiceConfiguration` bytecode and appends it
to that variant's `ScopedArtifact.CLASSES`.
Because the manifests are ordinary task-output artifacts, Gradle owns the scan → generate edges;
they survive configuration-cache reuse and isolated projects. Passing them as cross-project
`Provider`s or through a root-project extension would instead degrade to plain files during
configuration-cache reuse, silently dropping the scan tasks from the task graph.

Both tasks are annotated `@CacheableTask`; their deterministic inputs and outputs let Gradle
restore them from the build cache on clean builds.

### 3.5 Bytecode generation (three points that must hold)

**① No `<clinit>`.** All class literals appear only inside method bodies:

```java
public Class providerAt(int index) {
    if (index == 0) return HomeServiceImpl.class;   // ldc resolves only here
    if (index == 1) return VipHomeServiceImpl.class;
    ...
    return null;
}
```

If those references went into `<clinit>`, the configuration class itself would be marked
erroneous, and even after the module is installed it would forever be
`Could not initialize class` — the whole table is lost irreversibly.

**② `services()` contains interfaces only, one element per entry.** It is index-aligned with
`keys()`: a service with N keys appears N times, and every default implementation appears once
with an empty key. `ProviderRegistry` reads entries by index, which is what allows the default
key to own several providers. Interfaces are by convention pulled down into base and are always
resolvable.

**③ `@ProviderFactory` generates `INVOKESTATIC`**, not `Method.invoke`:

```
15: invokestatic  // Method AccountServiceImpl.create:()LAccountServiceImpl;
```

Neither the factory nor provider construction goes through reflection: no `getDeclaredMethod`,
no `Constructor.newInstance`, and the method name does not participate in obfuscation. Providers
without a factory are constructed by a direct `new` followed by `INVOKESPECIAL <init>`.

The generator uses `ClassWriter(COMPUTE_FRAMES)` and overrides `getCommonSuperClass` to return
`Object` — providers may come from uninstalled dynamic modules, so generation must not actually
load them.

### 3.6 AGP wiring

```kotlin
variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
    .use(generateTask)
    .toAppend(ScopedArtifact.CLASSES) { it.outputDir }
```

**Why `toAppend` instead of `toTransform`**: the generate task's input is plain-text manifests;
it does not read `ScopedArtifact.CLASSES` and only appends one class to classes. Therefore there
is no read-while-write cycle and no need to repackage all input classes. The operation is
registered for **every variant**, not once per build type: flavor variants share a build type and
each one needs the generated class in its own classes artifact.

**Manifest transport uses artifact views, not a cross-project collector.** A root-project
extension or a shared build service holding other projects' `Provider<RegularFile>` looks
harmless while everything is up to date, but under configuration-cache reuse (and especially
isolated projects) Gradle serializes such cross-project providers as plain files: the scan tasks
drop out of the task graph and stale manifests reach the generator. Publishing the manifest as a
typed artifact lets Gradle resolve it through the same variant selection it uses for classes,
which preserves the producer edges. The app declares its `dynamicFeatures` dependencies at
`projectsEvaluated` (their DSL values are final by then); declaring dependencies does not read
another project's model, so isolated projects stays clean.

---

## 4. Runtime

### 4.1 `ProviderConfiguration`: the configuration-table contract

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerAt(index: Int): Class<*>?   // resolve only, does not trigger <clinit>
    fun newProviderAt(index: Int): Any?     // resolve + instantiate
}
```

Entries are addressed by index into the `services()` / `keys()` arrays rather than by
`(service, key)`. Splitting "is the class present" from "can the class initialize" into two
methods is the prerequisite for the whole design to distinguish `MISSING` (module not installed,
retryable) from `DEAD` (`<clinit>` failed, irreversible). Index addressing additionally allows
the empty key to own several implementations: enumeration visits every index, while key-based
lookups take the first available entry.

### 4.2 On-demand probing and the DEAD cache

Implementation: `runtime/src/main/kotlin/top/jayvin/android/spi/internal/ProviderRegistry.kt`.

There is no pre-scan and no caching of `MISSING`; `ProviderState` is merely the result of one
`stateOf()` query:

| State | Meaning | Entered when |
|---|---|---|
| `MISSING` | Provider class is currently invisible | `providerAt` throws (for example `NoClassDefFoundError`) or returns null |
| `READY` | Provider class is resolvable | `providerAt` returns non-null |
| `DEAD` | Unavailable and retrying is pointless | `newProviderAt` throws `ExceptionInInitializerError`, or the message contains `Could not initialize class` |

For a key with several default implementations the states are aggregated: any `READY` wins, then
any `MISSING`; only an all-`DEAD` group reports `DEAD`.

`load()` walks every entry of `(service, key)` and calls `newProviderAt()` directly:

- Success → return the instance (the first available entry wins);
- `NoClassDefFoundError` (module not installed) → close that entry for this call, **not cached**,
  try the next entry and retry the whole lookup on the next call;
- `<clinit>` failure → add that entry index to the `dead` set and stop trying it for this process;
- Any other throwable from the factory (a business exception) → not cached, so the next call
  retries.

Entries are ordered by `(service, key, provider FQN)` before bytecode generation, so several
default implementations have a layout-independent, deterministic order. The generate task logs a
warning listing that order whenever a service has more than one default provider; availability
still wins over it (a `MISSING` or `DEAD` entry is skipped even when it sorts first).

So there is no `refresh()` and no window where "it is installed but the table is stale": after
a module is installed, the next `load()` succeeds naturally.
`DEAD` is the only state worth remembering, to avoid repeatedly triggering the same
initialization failure.

When `newProviderAt` throws `Failed resolution of` (module uninstalled), it is likewise not
cached and is retried next time.

`loadClass()` and `stateOf()` report resolution failures as `MISSING` and never record `DEAD`:
`DEAD` is only recorded by `load()` / `newProviderAt()` actually initializing a provider, which
is when an erroneous `<clinit>` becomes observable. `DEAD` is tracked per entry index, not per
key, because several default entries can share the empty key.

### 4.3 Initialization entry: the compileOnly placeholder class

The generated class is an ordinary `final` class implementing only `ProviderConfiguration`. The
facade constructs it lazily on the first lookup, using volatile double-checked locking, or eagerly
from `Application.onCreate` via `initialize()` (optional):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLoader.initialize()
    }
}
```

The `configuration-stub` module provides an empty class with the same name,
`top.jayvin.android.spi.generated.ServiceConfiguration`; runtime depends on it through
`compileOnly` and compiles a direct `new`:

```kotlin
val raw: Any = ServiceConfiguration()
val configuration = raw as? ProviderConfiguration ?: illegal()
```

At runtime:

- If the app plugin generated a same-name class → the ClassLoader resolves the real
  configuration table and the direct `new` succeeds;
- The placeholder is not published and not packaged, so it never duplicates the generated class;
- If the app did not apply the plugin and the class does not exist → `new` throws
  `NoClassDefFoundError`, which is caught, logged with `Log.e`, and degraded to an empty
  `ProviderConfiguration`; the facade APIs still work and simply return null / empty collections.

Key points:

- Provider implementations are still referenced by `ldc` / `new` / `INVOKESTATIC` in generated
  bytecode; the configuration class is also directly `new`-ed, so there is no `Class.forName`
  and no reflective provider construction anywhere.
- R8 sees the `new` reference and keeps the class when it exists; for the "not generated" case,
  use `-dontwarn` in `consumer-rules.pro`.
- Initialization timing is controlled by the app: `Application.onCreate` runs before any
  Activity, so the table is ready when business code first calls `load()`.
- The generator still emits no `<clinit>`: if static initialization referenced classes from
  uninstalled modules, the configuration class would immediately be marked erroneous and the
  whole table would be lost.

### 4.4 Dynamic module visibility

The framework does not depend on Play Core, does not call `SplitCompat`, and does not register
install listeners. After a dynamic module is installed, whether its classes are visible to the
current process is determined by the host's install channel (Play Feature Delivery handles it
on API 21+; other channels are the host's responsibility). As soon as the class is resolvable,
the next `load()` succeeds.

Consumers that need to access their own resources or Activities inside a split should handle
`SplitCompat` themselves per the Play Core documentation.

**Scenarios requiring no extra handling**: cold start (module installed earlier), process
restart after being killed (same).

On non-Play channels, dynamic delivery is handled by the host's own channel; providers already
packaged in the APK remain available.

In the sample, `:app` depends on Play Feature Delivery and drives `SplitInstallManager`;
`:modules:feature_camera` is the on-demand split.

### 4.5 Public facade

Implementation: `runtime/src/main/kotlin/top/jayvin/android/spi/ServiceLoader.kt`.

```kotlin
object ServiceLoader {
    const val DEFAULT_KEY = ""

    fun initialize()
    fun isInitialized(): Boolean

    fun <T : Any> load(service: Class<T>, key: String = DEFAULT_KEY): T?
    inline fun <reified T : Any> load(key: String = DEFAULT_KEY): T?
    fun <T : Any> loadClass(service: Class<T>, key: String = DEFAULT_KEY): Class<out T>?
    fun <T : Any> loadAll(service: Class<T>): Map<String, T>
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>>

    // Aligned with the java.util.ServiceLoader Provider model
    fun <T : Any> providerOf(service: Class<T>, key: String = DEFAULT_KEY): Provider<T>?
    fun <T : Any> findFirst(service: Class<T>): Provider<T>?
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>>
    fun <T : Any> providers(service: Class<T>): List<Provider<T>>

    fun stateOf(service: Class<*>, key: String = DEFAULT_KEY): ProviderState?
    fun snapshot(): List<Triple<String, String, ProviderState>>
}
```

Initialization is lazy: the first lookup (or an explicit `initialize()` call) loads the
configuration once under double-checked locking. Both paths are idempotent. The facade never
throws: a missing or invalid generated configuration is logged as an error and degraded to an
empty configuration, after which every lookup returns null / an empty collection. Eager
initialization simply moves that diagnosis from the first lookup to `Application.onCreate`.

Every non-inline public method is `@JvmStatic`; Java lacks reified generics, so pass Class
literals; `Sequence` is awkward to iterate in Java, so `providers()` returns a `List`:

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

Kotlin keeps the inline `load<HomeService>()` form and the `Sequence` from `stream()`.

`load(service)` has one fallback: when no key is specified (empty string), no default entry is
available, and the interface has exactly one available non-default key, use it directly, so
single-implementation interfaces do not need a key. Several default implementations are tried in
declaration order instead.

**Deliberately not provided**:

| Not provided | Reason |
|---|---|
| `loadOrInstall` / `awaitService` | Installation is asynchronous, so the first call cannot return an instance; mixing it in makes the return value ambiguous (downloading / unregistered / never available) and requires a cross-module wait queue. Business code composes it itself (see `MainActivity.openCamera` in the sample app) |
| Annotation-level `singleton` | The cache key would be the provider's `Class`; after a split is uninstalled and reinstalled, the old instance prevents the old ClassLoader from being collected. Providers needing a cache can use `@ProviderFactory` to return a cached object |
| `callMethod(key, args...)` | Purely string-based method lookup conflicts with "metadata via class literals" |
| `getService(service, context)` | Dependencies come from the `@ProviderFactory` body, avoiding the "factory only takes effect on first use for singletons" pitfall |

---

## 5. Key JVM semantics (whether the design holds)

**① `ldc X.class` only resolves; it does not initialize.**
So `providerAt` naturally splits "is the class in the dex" from "can the class initialize".

**② Class initialization failure is one-shot.**
After `<clinit>` throws, the class is marked erroneous, and any later access is
`Could not initialize class` — irreversible within the process. Therefore
`ExceptionInInitializerError` must be classified as `DEAD` and must not be retried.

**③ Failed symbolic-reference resolution is not cached.**
After the module is installed, executing the same `ldc` again leaves the constant-pool entry
unresolved, and this time the class is found → success. This is the underlying basis for "the
next `load()` succeeds automatically after installation".

Only the three together imply: **literals must live in method bodies, putting them in
`<clinit>` poisons the whole table, and failures can be retried**.

---

## 6. Comparison with mainstream solutions

| Dimension | This solution | WMRouter | ARouter |
|---|---|---|---|
| Registry generation | Source scan + manifest + generated bytecode | APT + Gradle plugin merge | APT + runtime dex scan |
| Runtime scanning | None | None | Yes (dex scan) |
| Reflection | None (direct `new` / `INVOKESTATIC`; no `Class.forName`, no `Constructor.newInstance`) | `Method.invoke` to call providers | Reflection to instantiate |
| Dynamic module support | Late-bound providers: availability is probed on every `load()` and `MISSING` is never cached; only the irreversible `DEAD` (`<clinit>` failure) is remembered. Installation and split visibility are owned by the host (no Play Core dependency) | Manual only (`ServiceLoader.put()`) | None |
| APT dependency | No | Yes | Yes |
| Cross-module aggregation | Plain-text manifests | Generated-class merge | Route table + dex scan |

Scope of "dynamic module support": the framework resolves providers that become visible after
a split is installed, but it does not trigger, track, or force installation. The demo's `:app`
owns Play Feature Delivery (`SplitInstallManager`) and any `SplitCompat` calls; `runtime` has
no Play dependency, and `:modules:feature_camera` is the on-demand sample split.

WMRouter's `ServiceLoader.put()` is public static and can manually integrate dynamic modules,
but its table stores `Class` literals and initializes once (`mHasInit`); once any `ldc` in
`<clinit>` fails, the whole table is fatal — this is the direct reason this design insists that
"literals never go into `<clinit>`".

---

## 7. Boundaries and known risks

| Risk | Description | Mitigation |
|---|---|---|
| Interfaces must live in base | If an interface literal in `services()` comes from a dynamic module, the whole table breaks | Convention + code review (no build-time ownership check yet) |
| Wrong package when the interface is not imported | A wrong FQN is not exposed at build time | Fail the build when `import a.b.*` exists; otherwise require explicit imports |
| Scan scope | Every variant's declared static sources are scanned (`src/main` + build-type / flavor overlays from `variant.sources`); generated source directories are skipped; `META-INF/services` is only recognized in source dirs, not dependency artifacts | Documented behavior; the demo's four-variant self-check covers it |
| `@ProviderFactory` signature unchecked | Argument count and return type are not validated; a mismatch produces a descriptor that fails at runtime | Keep factories no-arg and returning the enclosing type; violations surface as `NoSuchMethodError` when the provider is first loaded |
| Per-variant aggregation | A module variant is aggregated only when its build type matches and all of its flavor dimensions match the app variant; a dependency with a dimension the app does not declare is skipped (as AGP would) | Documented behavior; dynamic features must declare the base module's flavor dimensions |
| Nested classes / `typealias` unsupported | The scanner resolves top-level types | Documented limitation; declare services as top-level interfaces |
| R8 missing classes | Base referencing uninstalled module classes warns | Add `-dontwarn` per module (sample provided) |
| Non-Play channels | The framework has no built-in install channel; split visibility depends on the host | Host implements its own delivery channel; providers packaged in the APK remain READY |
| Configuration cache / isolated projects | Both are supported: manifests travel as typed artifacts owned by Gradle's variant resolution, so producer edges survive configuration-cache reuse; no project reads another project's model. Verified with store/reuse, source changes, four variants, and AAB builds | — |

---

## 8. Verification (executed locally)

| Item | Command / result |
|---|---|
| Toolchain compile | `./gradlew :plugin:compileKotlin` ✅ |
| Runtime unit tests | `./gradlew :runtime:testDebugUnitTest` → 9 passed on the JVM (registry behavior + facade never-throws / degradation tests) |
| Sample APK | `:app:assembleFreeDebug` ✅; manifest confirms the Application; the configuration class is generated by `FreeDebugScanProviders` / `FreeDebugGenerateServiceConfiguration` and packed into the base dex |
| Sample AAB | `:app:bundleFreeDebug` ✅, `base` / `feature_camera` splits |
| Variant matrix | All four of `assembleFreeDebug` / `assemblePaidDebug` / `assembleFreeRelease` / `assemblePaidRelease` ✅ (also from `clean`); `javap` + `dexdump` show each APK contains only its flavor's `ChannelServiceImpl`, debug APKs additionally contain `DebugServiceImpl`, release APKs do not |
| On-device UI test | All four APKs installed side by side on a Xiaomi M2012K11C (Android 13); every self-check screen reports `SELF-CHECK PASS 12/12`; screenshots in `build/ui-test/`; tapping "Re-run checks" and "Camera module" does not crash (split install fails gracefully with error -14 on a sideloaded APK) |
| Incremental build | A second `:app:assembleFreeDebug` leaves all `ScanProviders` / `GenerateServiceConfiguration` tasks UP-TO-DATE, confirming the cacheable task wiring |
| Incremental rebuild | Adding/removing a provider reruns only that module's scan + generate (and downstream packaging); the other modules' scans stay UP-TO-DATE, and reverting restores the tasks FROM-CACHE |
| Build cache | `clean` rebuild restores every scan + generate task FROM-CACHE; a byte-identical copy of the project at another absolute path also restores them, confirming the RELATIVE input path sensitivity and deterministic outputs |
| Configuration cache | Store + reuse verified; source changes under `--configuration-cache` still rerun scan/generate; all four variants build in one invocation; `:app:bundleFreeDebug` succeeds |
| Isolated projects | `-Dorg.gradle.unsafe.isolated-projects=true` passes store/reuse, added/removed providers, all four variants, and AAB builds under the same configuration cache |
| Dynamic feature without plugin | Removing the plugin from a `dynamicFeatures` project still builds (standard and isolated modes); the table simply contains no provider from that feature, and restoring the plugin adds it back |
| Generated bytecode | `javap -c` confirms: **no `<clinit>`**, `providerAt` only uses `ldc`, `newProviderAt` uses `INVOKESTATIC` for `@ProviderFactory`; `services()` / `keys()` are index-aligned (8 entries in debug, 7 in release) |
| Split correctness | `dexdump` confirms `CameraServiceImpl` only exists in `feature_camera/dex`; base dex only has references |
| Standard SPI config | `modules/core-common`'s `META-INF/services/...LegacyService` is scanned into the manifest (`LegacyService → LegacyServiceImpl`); generated bytecode contains `ldc LegacyServiceImpl` and `new LegacyServiceImpl`; `MainActivity` calls `ServiceLoader.load(LegacyService::class.java)` and gets a non-null instance |

### 8.1 Full regression matrix (run after major plugin changes)

Any change to scanning, aggregation, artifact wiring, or caching must re-run this matrix. Every
step below was executed for the artifact-based transport rewrite (Gradle 8.9 / AGP 8.7.3).

1. **Framework publish + JVM tests**
   ```bash
   ./gradlew :annotation:publish :runtime:publish :plugin:publish
   ./gradlew :runtime:testDebugUnitTest --rerun-tasks
   ```
2. **Clean build of all variants** (also verifies the AAB)
   ```bash
   ./gradlew clean :app:assembleFreeDebug :app:assemblePaidDebug :app:assembleFreeRelease :app:assemblePaidRelease
   ./gradlew :app:bundleFreeDebug
   ```
3. **Generated classes, per variant** (`javap -p -c`): only the flavor's `ChannelServiceImpl`;
   `DebugServiceImpl` present in debug and absent in release; `services()` and `keys()` lengths
   equal (8 debug / 7 release); no `<clinit>`.
4. **APK/AAB content** (`dexdump`): `CameraServiceImpl` exists only in the feature split, and no
   `services.txt` artifact leaks into any APK/AAB.
5. **Incremental**: add a temporary `@ServiceProvider` to one module, build, and confirm only
   that module's scan + generate rerun; remove it and confirm FROM-CACHE plus a reverted class.
6. **Build cache**: a `clean` rebuild restores every scan + generate FROM-CACHE; a byte-identical
   copy of the project at another absolute path is also FROM-CACHE.
7. **Configuration cache (standard)**: store, reuse, then add/remove a provider while the entry is
   reused; scan + generate must rerun. Repeat with all four variants in one invocation and with
   `:app:bundleFreeDebug`.
8. **Isolated projects**: repeat steps 6–7 with
   `-Dorg.gradle.unsafe.isolated-projects=true` (Gradle 9.7+ uses `--isolated-projects`).
9. **Dynamic feature tolerance**: remove the plugin from a `dynamicFeatures` project; the build
   must still succeed and that feature must contribute 0 providers; restore it.
10. **On-device matrix**: install all four APKs, launch each, verify `SELF-CHECK PASS 12/12` and
    the variant-specific values (flavor provider, build-type isolation, entry counts); tap
    "Re-run checks" and "Camera module" (graceful error -14 on a sideloaded APK); keep the
    screenshots under `build/ui-test/` and confirm no `AndroidRuntime` errors.

Known gap: the `MISSING → READY` transition after a real Play dynamic install cannot be exercised
with a sideloaded APK; the runtime unit tests cover the equivalent state change with a fake
`ProviderConfiguration`.
