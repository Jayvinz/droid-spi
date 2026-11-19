# How Droid-spi works internally: artifact transport, ASM, and JVM class initialization

*A deep dive into the pipeline behind a compile-time SPI for Android: why manifests travel as
Gradle artifacts, why the generated class must not have a static initializer, and how the
configuration cache and isolated projects shaped the design.*

---

The [first article](01-introducing-droid-spi.md) showed what `Droid-spi` does. This one is about
how it is built, and why several obvious implementations are wrong.

The project has three modules: `annotation` (two plain Java annotations), `runtime` (the
`ServiceLoader` facade and `ProviderRegistry`), and `plugin` (the Gradle plugin, scan tasks, and
an ASM generator). A fourth module, `configuration-stub`, compiles a placeholder class so the
runtime can compile a direct `new` against a class that does not exist yet.

## Stage 1: scan per module, per variant

For every Android module and every variant, the plugin registers a `{variant}ScanProviders`
task. Its input is the variant's declared source directories, taken from `variant.sources`
(`java.static`, `kotlin.static`, and `resources.static` when `META-INF/services` scanning is
enabled). That detail matters: using `src/main` directly would miss `src/debug`, `src/free`,
`src/paid`, and every other overlay.

The scanner is intentionally not an AST parser. It strips comments and masks string contents,
then matches annotations and finds the enclosing class by bracket depth. That keeps the plugin
free of Kotlin compiler internals and makes it work identically for Java and Kotlin sources.

Output is `build/generated/spi/{variant}/services.txt`, one line per entry:

```
service <TAB> provider <TAB> key <TAB> factory
```

An empty key means the default implementation; an empty factory means "call the no-arg
constructor".

## Stage 2: manifests travel as artifacts, not as project models

The first implementation used a root-project extension and a shared build service: each module
published its manifest to a central map, and the app read it at the end of configuration. It
worked in a warm build and broke under configuration-cache reuse, especially with isolated
projects. The reason is subtle:

- A `Provider<RegularFile>` produced by an already-configured project, held by another project's
  task input, is serialized by Gradle as a plain file path when the configuration cache is
  reused.
- The scan task then drops out of the task graph, while the generate task still reads the old
  file. The build "succeeds" with stale data.

The fix is to use the mechanism Gradle already models correctly: **variant-aware artifact
resolution**.

- Each module variant attaches its `services.txt` to `{variant}RuntimeElements` with a custom
  artifact type, `spi-manifest`.
- The application's generate task takes an `artifactView` of its `variant.runtimeConfiguration`,
  filtered by that artifact type. Resolution is transitive and lazy; non-plugin modules simply
  contribute nothing.
- Dynamic features have no runtime edge into the app, so the app adds a dependency on every path
  in its own `dynamicFeatures` DSL and takes the same filtered view of the feature's runtime
  elements.
- The generate task writes class files directly into its own variant via
  `toAppend(ScopedArtifact.CLASSES)` and does not read the `CLASSES` input, so there is no cycle.

This is more code than a shared service, but it is the only shape that keeps the scan → generate
edges intact across configuration-cache reuse and isolated projects.

## Stage 3: generate bytecode, carefully

The generator emits a class with a fixed fully-qualified name,
`top.jayvin.android.spi.generated.ServiceConfiguration`, implementing `ProviderConfiguration`:

```kotlin
interface ProviderConfiguration {
    fun services(): Array<Class<*>>
    fun keys(): Array<String>
    fun providerAt(index: Int): Class<*>?
    fun newProviderAt(index: Int): Any?
}
```

`services()` and `keys()` must be **index-aligned, one element per entry**, and the provider
methods take that index. If `HomeService` has two keys, the interface appears twice; several
default implementations each appear once with an empty key. Index addressing is what keeps them
from collapsing: key-based dispatch could only ever return one of them. Returning each interface
once at startup produces `Broken provider configuration: services=X keys=Y`.

### The no-`<clinit>` rule

This is the most important invariant in the project.

A naïve generator emits a static array in `<clinit>`:

```java
static final Class<?>[] SERVICES = { HomeService.class, CameraService.class, ... };
```

On Android, if `CameraService`'s implementation lives in a dynamic module that is not installed,
resolving that literal fails with `NoClassDefFoundError` **during class initialization**. The JVM
then marks `ServiceConfiguration` erroneous for the rest of the process. Every other provider is
broken, and installing the module later does not help.

So the generator never emits a static initializer. All class literals are `ldc` instructions
inside method bodies, where resolution is lazy and recoverable. The ASM `ClassWriter` is also
forced to return `java/lang/Object` from `getCommonSuperClass`, because the default
implementation would try to load the implementation classes on the build JVM — exactly the
classes that may not exist.

### Factories

`@ProviderFactory` marks a static no-arg method that returns the implementation type. The
generator emits `INVOKESTATIC` with a descriptor assembled statically, so no reflective
`Method.invoke` is needed. The scanner enforces "static and at most one"; the no-arg and
return-type requirements are not validated at build time, and a violation fails later as
`NoSuchMethodError`.

## Stage 4: runtime probing

`ProviderRegistry` deliberately does almost nothing at startup:

- No pre-scan. Constructing the registry just stores the configuration.
- `load()` calls `newProviderAt()` directly for every entry of `(service, key)`, returning the
  first one that works. If the class is absent, `NoClassDefFoundError` is
  caught and converted to `null`; the next call retries. That is why a newly installed dynamic
  module works without any refresh.
- `MISSING` is never cached. `READY` is not cached either; providers are instantiated per call,
  matching `ServiceLoader` semantics.
- `DEAD` **is** cached: once a provider's `<clinit>` throws, the JVM has marked the class
  erroneous and retrying cannot succeed.
- `stateOf()` probes on demand and returns `READY` / `MISSING` / `DEAD`.

The facade is total by design. Calling any lookup before `initialize()` logs a warning once and
returns `null` / an empty collection. A missing or invalid generated class is logged as an error
and replaced with an empty configuration. This is a deliberate API decision: a service discovery
layer should not crash an app because a module is absent.

## Caching and correctness

Both tasks are `@CacheableTask` and declare source inputs with `PathSensitivity.RELATIVE`, so
build-cache entries are relocatable. The verification matrix in the design document covers:

- clean builds of all four variants, APK/AAB content checks;
- incremental add/remove of a `@ServiceProvider`;
- build-cache hits with a relocated project directory;
- configuration cache store and reuse, with and without isolated projects;
- on-device self-check across every variant.

The last one is not decorative: it is the only way to catch a variant whose generated class was
never appended, because the build still succeeds while `ServiceConfiguration` is missing at
runtime.

## Tradeoffs

- **Compile-time only.** Providers must be visible to the build. Runtime-loaded plugins are out
  of scope.
- **Explicit keys are unique.** Two providers declaring the same non-empty key fail the build.
  The default key may have several implementations; `load()` returns the first available and
  `providers()` enumerates them all.
- **Interfaces must live in base.** A service interface in a dynamic module would make the table
  unresolvable before installation.
- **Descriptive, not magical.** There is no scope, lifecycle, or constructor injection. If your
  graph is fully installed at build time, use Hilt or Dagger. `Droid-spi` is for the part of the
  graph that is *not* guaranteed to be in the process.

The implementation is intentionally small enough to read in an afternoon: a scanner, an ASM
writer, a registry, and a facade. The hard part was not the code; it was discovering which Gradle
and JVM assumptions do not survive dynamic delivery and configuration caching.

Repository: [Jayvinz/droid-spi](https://github.com/Jayvinz/droid-spi).
Design document: [`docs/droid-spi-design.md`](../droid-spi-design.md).
