# Android SPI ServiceLoader · Design (v2, implemented)

> A compile-time provider configuration table for AAB multi-module projects. Naming follows
> the SPI vocabulary of `java.util.ServiceLoader`.
> Implementation: `android-spi/`; sample project: `android-spi/` (`app/` + `modules/` are the
> demo, `annotation` / `runtime` / `plugin` are the framework, all in a single build).
> Core traits: **zero `Class.forName` (class literals for both configuration and providers),
> zero APT/KSP, zero third-party jar scanning, metadata via class literals, providers probed
> on demand**.

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
| C1 | **No `Class.forName`** | Reflection bypasses R8 visibility and throws `Error` that is hard to handle; the configuration class compiles a direct `new` against a compileOnly placeholder |
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
┌─ Compile time (per module) ──────┐   ┌─ Aggregation (app only) ──────┐   ┌─ Runtime ────────────┐
│ src/main/**/*.kt|java            │   │  services.txt (all modules)   │   │ Application.onCreate │
│   ↓ lexical scan + type resolve  │   │   ↓ merge + conflict check    │   │   ↓ initialize()     │
│ src/main/resources/META-INF/     │   │ ServiceConfiguration.class    │──▶│ on-demand load() →READY│
│   services/**  ↓ standard SPI    │──▶│   ↓ toAppend(CLASSES)         │   │   ↓ INSTALLED        │
│ services.txt (service/provider/  │   │ base dex → APK/AAB            │   │ next load() → READY  │
│           key/factory)           │   │                               │   │                      │
└──────────────────────────────────┘   └───────────────────────────────┘   └──────────────────────┘
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
the `service =` prefix).

**Three hard constraints for `@ProviderFactory`** (validated at build time):

1. Must be a static method (Kotlin needs `@JvmStatic`, Java needs `static`);
2. Must take no arguments;
3. The return type must be the enclosing class itself.

The third constraint lets the generated `INVOKESTATIC provider.factory()L<provider>;`
descriptor be assembled statically. Loosening it to a supertype would require resolving the
return type FQN: little benefit, large error surface.

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
| Scan directories | `src/main/resources` is added to `sourceDirs`; also accepts `META-INF/services/` at any depth under `src/main/java` / `src/main/kotlin` |
| Switch | `spi { scanMetaInfServices = true }`, enabled by default |
| Scope | Reads only these text files; **does not unzip jars / aars, does not scan classes**, preserving C3 |
| `key` | Standard SPI has no such concept → `""` (default variant) |
| Instantiation | No factory → no-arg constructor; **the implementation must have a public no-arg constructor** |
| Dedup | Same provider declared by an annotation and by SPI config (identical line) is deduplicated; different implementations competing for the same `(service, key)` fail in the aggregation task |
| Validity | A provider name not matching `^[\w.$]+$` fails the build with the file name and line content |

Each config entry maps to `ProviderEntry(service, provider, "", "")` and then follows exactly
the same path as annotation entries: module manifest → aggregation validation → generated
`providerOf` / `newProvider`. Runtime behavior is identical.

Prerequisite: the module must apply the `top.jayvin.android.spi` plugin (otherwise there is no
`xxScanProviders` task). `modules/core-common` is the minimal example with SPI config only and
no annotations — its manifest output is:

```
com.example.core.api.LegacyService  com.example.core.common.LegacyServiceImpl
```

### 3.4 Manifests and aggregation

Each module has one `ScanProvidersTask` producing a deterministic manifest (sorted, no
timestamps):

```
com.example.core.api.AccountService	com.example.feature.account.AccountServiceImpl		create
com.example.core.api.AccountService	com.example.feature.account.GuestAccountServiceImpl	guest
```

Four columns: `service \t provider \t key \t factory` (an empty `key` means the default
variant; an empty `factory` means no-arg construction).

`GenerateServiceConfigurationTask` merges all manifests in the app module: it validates that
`(service, key)` is globally unique (a conflict fails the build and lists both providers),
then generates the `ServiceConfiguration` bytecode.

### 3.5 Bytecode generation (three points that must hold)

**① No `<clinit>`.** All class literals appear only inside method bodies:

```java
public Class providerOf(Class service, String key) {
    if (service == HomeService.class) {
        if ("".equals(key)) return HomeServiceImpl.class;   // ldc resolves only here
        ...
    }
    return null;
}
```

If those references went into `<clinit>`, the configuration class itself would be marked
erroneous, and even after the module is installed it would forever be
`Could not initialize class` — the whole table is lost irreversibly.

**② `services()` contains interfaces only.** Interfaces are by convention pulled down into
base and are always resolvable.

**③ `@ProviderFactory` generates `INVOKESTATIC`**, not `Method.invoke`:

```
15: invokestatic  // Method AccountServiceImpl.create:()LAccountServiceImpl;
```

Zero reflection, no `getDeclaredMethod`, the method name is not involved in obfuscation.

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
is no read-while-write cycle and no need to repackage all input classes.

**Cross-project collection uses `projectsEvaluated` rather than configuration-time
`findByName`**: Gradle evaluates projects in settings order, so app may be configured before
feature modules; at that time `findByName("DebugScanProviders")` returns null and silently
drops the whole module. Each module registers its `Provider<RegularFile>` on the root
extension, and app wires them all at `projectsEvaluated` — by then every project is
configured, and the Provider carries its producer dependency, so the scan → generate edge is
created automatically in the task graph.

---

## 4. Runtime

### 4.1 `ProviderConfiguration`: the configuration-table contract

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerOf(service: Class<*>, key: String): Class<*>?   // resolve only, does not trigger <clinit>
    fun newProvider(service: Class<*>, key: String): Any?         // resolve + instantiate
}
```

Splitting "is the class present" from "can the class initialize" into two methods is the
prerequisite for the whole design to distinguish `MISSING` (module not installed, retryable)
from `DEAD` (`<clinit>` failed, irreversible).

### 4.2 On-demand probing and the DEAD cache

There is no pre-scan and no caching of `MISSING`; `ProviderState` is merely the result of one
`stateOf()` query:

| State | Meaning | Entered when |
|---|---|---|
| `MISSING` | Provider class is currently invisible | `providerOf` throws `NoClassDefFoundError` or returns null |
| `READY` | Provider class is resolvable | `providerOf` returns non-null |
| `DEAD` | Unavailable and retrying is pointless | `newProvider` throws `ExceptionInInitializerError`, or the message contains `Could not initialize class` |

`load()` calls `newProvider()` directly:

- Success → return the instance;
- `NoClassDefFoundError` (module not installed) → return null, **not cached**, retry on the
  next call;
- `<clinit>` failure → add to the `dead` set and stop trying for this process.

So there is no `refresh()` and no window where "it is installed but the table is stale": after
a module is installed, the next `load()` succeeds naturally.
`DEAD` is the only state worth remembering, to avoid repeatedly triggering the same
initialization failure.

When `newProvider` throws `Failed resolution of` (module uninstalled), it is likewise not cached
and is retried next time.

### 4.3 Initialization entry: the compileOnly placeholder class

The generated class is an ordinary `final` class implementing only `ProviderConfiguration`; the
app initializes it explicitly from `Application.onCreate`:

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
  anywhere.
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

### 4.5 Public facade

```kotlin
object ServiceLoader {
    fun <T : Any> load(service: Class<T>, key: String = ""): T?        // default / specific variant
    inline fun <reified T : Any> load(key: String = ""): T?
    fun <T : Any> loadClass(service: Class<T>, key: String = ""): Class<out T>?
    fun <T : Any> loadAll(service: Class<T>): Map<String, T>
    fun <T : Any> loadAllClasses(service: Class<T>): List<Class<out T>>

    // Aligned with the java.util.ServiceLoader Provider model
    fun <T : Any> providerOf(service: Class<T>, key: String = ""): Provider<T>?
    fun <T : Any> findFirst(service: Class<T>): Provider<T>?
    fun <T : Any> stream(service: Class<T>): Sequence<Provider<T>>

    fun stateOf(service: Class<*>, key: String): ProviderState?
}
```

Everything returns `null` instead of throwing: an unavailable service is a normal state, not
an error.

All public Java-facing methods are `@JvmStatic`, but Java lacks reified generics, so pass Class
literals; `Sequence` is awkward to iterate in Java, so `providers()` returns a `List`:

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

Kotlin keeps the inline `load<HomeService>()` form and the `Sequence` from `stream()`.

`load(service)` has one fallback: when no key is specified (empty string) and the interface has
exactly one READY provider, use it directly, so single-implementation interfaces do not need a
key.

**Deliberately not provided**:

| Not provided | Reason |
|---|---|
| `loadOrInstall` / `awaitService` | Installation is asynchronous, so the first call cannot return an instance; mixing it in makes the return value ambiguous (downloading / unregistered / never available) and requires a cross-module wait queue. Business code composes it itself (6 lines in the sample app) |
| Annotation-level `singleton` | The cache key would be the provider's `Class`; after a split is uninstalled and reinstalled, the old instance prevents the old ClassLoader from being collected. Providers needing a cache can use `@ProviderFactory` to return a cached object |
| `callMethod(key, args...)` | Purely string-based method lookup conflicts with "metadata via class literals" |
| `getService(service, context)` | Dependencies come from the `@ProviderFactory` body, avoiding the "factory only takes effect on first use for singletons" pitfall |

---

## 5. Key JVM semantics (whether the design holds)

**① `ldc X.class` only resolves; it does not initialize.**
So `providerOf` naturally splits "is the class in the dex" from "can the class initialize".

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
| Reflection | None (compileOnly placeholder + direct `new`; providers via class literals) | `Method.invoke` to call providers | Reflection to instantiate |
| Dynamic module support | Built in (on-demand probing + DEAD cache) | None | None |
| APT dependency | No | Yes | Yes |
| Cross-module aggregation | Plain-text manifests | Generated-class merge | Route table + dex scan |

WMRouter's `ServiceLoader.put()` is public static and can manually integrate dynamic modules,
but its table stores `Class` literals and initializes once (`mHasInit`); once any `ldc` in
`<clinit>` fails, the whole table is fatal — this is the direct reason this design insists that
"literals never go into `<clinit>`".

---

## 7. Boundaries and known risks

| Risk | Description | Mitigation |
|---|---|---|
| Interfaces must live in base | If an interface literal in `services()` comes from a dynamic module, the whole table breaks | Convention + review; v2 adds build-time ownership validation |
| Wrong package when the interface is not imported | A wrong FQN is not exposed at build time | Fail the build when `import a.b.*` exists; otherwise require explicit imports |
| Only `src/main` is scanned | Flavor directories and custom source sets are not covered; `META-INF/services` is only recognized in source dirs, not dependency artifacts | v2 will use `variant.sources` |
| Aggregation per buildType | Flavor-exclusive services do not enter the table | Multi-flavor projects must align per variant |
| Nested classes / `typealias` unsupported | The scanner resolves top-level types | Documented limitation; declare services as top-level interfaces |
| R8 missing classes | Base referencing uninstalled module classes warns | Add `-dontwarn` per module (sample provided) |
| Non-Play channels | Dynamic delivery does not apply | Degrades to a static configuration table; all providers are READY by nature |
| Configuration cache | Uses `projectsEvaluated` and cross-project Providers | Do not enable configuration cache before migrating to isolated projects |

---

## 8. Verification (executed locally)

| Item | Command / result |
|---|---|
| Toolchain compile | `./gradlew :plugin:compileKotlin` ✅ |
| Runtime unit tests | `./gradlew :runtime:testDebugUnitTest` → 7 passed (returns null while uninstalled, succeeds after install without refresh, retries on every load, DEAD not retried, unknown key, stream does not instantiate, etc.) |
| Sample APK | `:app:assembleDebug` ✅; manifest confirms the Application; the configuration class is generated by `initDebugScanProviders` / `DebugGenerateServiceConfiguration` and packed into the base dex |
| Sample AAB | `:app:bundleDebug` ✅, `base` / `feature_camera` splits |
| Generated bytecode | `javap -c` confirms: **no `<clinit>`**, `providerOf` only uses `ldc`, `newProvider` uses `INVOKESTATIC` for `@ProviderFactory` |
| Split correctness | `dexdump` confirms `CameraServiceImpl` only exists in `feature_camera/dex`; base dex only has references |
| Standard SPI config | `modules/core-common`'s `META-INF/services/...LegacyService` is scanned into the manifest (`LegacyService → LegacyServiceImpl`); generated bytecode contains `ldc LegacyServiceImpl` and `new LegacyServiceImpl`; `MainActivity` calls `ServiceLoader.load(LegacyService::class.java)` and gets a non-null instance |
