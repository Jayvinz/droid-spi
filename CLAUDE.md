# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Android AAB / 多模块工程下的**编译期 SPI（ServiceLoader）框架**。每个模块 apply Gradle 插件扫描
`@ServiceProvider` 注解与源码目录下的 `META-INF/services/` 文本，app 模块把所有清单聚合成一个
固定全类名的 `ServiceConfiguration` class（用 ASM 生成字节码），由应用在
`Application.onCreate` 里调用 `ServiceLoader.initialize()` 直接实例化。
运行时 `ServiceLoader` 门面从这张表按需取 provider；模块装好后下一次 `load()` 自然成功，无需刷新。
配置类和 provider 都由类字面量引用（`new` / `ldc`），全程不做 `Class.forName`。

设计文档：`docs/android-service-discovery-design.md`。用户文档：`README.md`（术语对照、接入步骤、已知限制）。

## 常用命令

```bash
# 修改插件本体（annotation / runtime / plugin）后，必须先发布到 local-repo，
# 同一 build 里的 app/modules 是从 local-repo 消费插件的，不是 project 依赖。
./gradlew :annotation:publish :runtime:publish :plugin:publish

./gradlew :app:assembleDebug      # demo APK
./gradlew :app:bundleDebug        # demo AAB（含 feature_camera 分包）

# 运行期单元测试（全部）
./gradlew :runtime:testDebugUnitTest
# 单个测试类 / 单个测试方法
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest"
./gradlew :runtime:testDebugUnitTest --tests "top.jayvin.android.spi.ProviderRegistryTest.模块未安装时是 MISSING，安装后无需 refresh 直接 load 成功"
```

Windows 上用 `gradlew.bat`。`local-repo/` 被 gitignore，**全新 clone 后直接构建 `:app` 会失败**，
必须先跑上面的 publish。`plugin` 目前没有测试源码；`runtime` 的测试是纯 JVM 单元测试，
用假的 `ProviderConfiguration` 模拟"模块未安装"，不需要真机/模拟器。

验证生成物：

```bash
javap -p -c app/build/intermediates/classes/debug/DebugGenerateServiceConfiguration/top/jayvin/android/spi/generated/ServiceConfiguration.class
```

## 模块结构

单一 Gradle build，`settings.gradle.kts` 统一 include：

| 路径 | 作用 |
|---|---|
| `annotation/` | 纯 Java 注解 `@ServiceProvider` / `@ProviderFactory`，零依赖，`RetentionPolicy.CLASS` |
| `runtime/` | `ServiceLoader` 门面、`ProviderRegistry` 按需探测（Android Library，不依赖 Play Core） |
| `plugin/` | Gradle 插件：`SpiPlugin`、扫描任务、ASM 生成器，`java-gradle-plugin` |
| `configuration-stub/` | runtime 的 compileOnly 占位类（同名 `ServiceConfiguration`，不发布、不打包） |
| `app/` | demo application 模块，唯一注册生成任务的模块；Application 调 `ServiceLoader.initialize()` |
| `modules/core-api/` | 所有 service **接口**（必须常驻 base） |
| `modules/core-common/` | 只有 `META-INF/services` 配置的常驻 library 示例 |
| `modules/feature-*/` | provider 实现；`feature_camera` 是 on-demand 动态模块 |
| `local-repo/` | 插件本体 publish 产物（本地 maven 仓库，gitignored） |

## 编译期流水线

1. **每个模块**（library / dynamic-feature / application）在 `SpiPlugin` 里注册 `{buildType}ScanProviders`
   （如 `debugScanProviders`），扫描 `src/main/java`、`src/main/kotlin`（`scanMetaInfServices=true` 时加
   `src/main/resources`），产出 `build/generated/spi/{buildType}/services.txt`。
   清单格式：`service \t provider \t key \t factory`（key 为空串表示默认变体）。
2. **只有 application 模块**注册 `{buildType}GenerateServiceConfiguration`，读取所有模块的清单，
   按 `(service, key)` 去重校验（重复即构建失败），用 ASM 生成 class。
3. 清单在 root project 的 `spiRoot` extension 收集，`projectsEvaluated` 之后才接到生成任务输入，
   避免配置期跨工程求值顺序问题；生成任务通过 `toAppend(ScopedArtifact.CLASSES)` 输出字节码，
   不读 `CLASSES` 输入，因此任务图无环。

**聚合按 buildType 而非 variant**：`debug`/`release` 各一份，flavor 共享。多 flavor 需改成按 variant 对齐。
扫描只覆盖 `src/main`，不覆盖 `src/debug/` 等 flavor 独有目录。

## 运行时路径

`ServiceConfiguration` 是普通类（不再继承 ContentProvider），由应用在 `Application.onCreate`
调用 `ServiceLoader.initialize()`，后者直接 `new top.jayvin.android.spi.generated.ServiceConfiguration`
（runtime 用 `configuration-stub` 编译期占位；运行时由 app 生成的同名类顶替）。
该类缺失（app 未 apply 插件）时捕获异常、打日志，并降级为空配置。
`ProviderRegistry` 不预扫描、不缓存 `MISSING`：

- `load()` 直接调 `newProvider()`；类未安装时抛 `NoClassDefFoundError`，捕获后返回 `null`，
  下次调用再试——所以模块装好后下一次 `load()` 自动成功，没有 `refresh()`。
- 只缓存 `DEAD`（`<clinit>` 失败，JVM 已标记 erroneous，不可逆）；`stateOf()` 每次按需探测，
  返回 `READY` / `MISSING` / `DEAD`。
- `load()` 未就绪返回 `null`（不抛异常）；默认 key 是空串（`ServiceLoader.DEFAULT_KEY`），
  默认 key 找不到时若该接口只有一个可用 provider 则降级用它。
- 不再注册 Play 安装监听；`SplitInstallStateUpdatedListener` / `InstallWatcher` 已移除。

## 修改时必须守住的约束

这些是正确性红线，不是风格偏好：

1. **生成的 class 绝不能有 `<clinit>`**。provider 类字面量只能出现在方法体里（`ldc` / `new` / `INVOKESTATIC`）。
   否则动态模块未安装时配置类被 JVM 标记 erroneous，整表永久报废。`ConfigurationClassGenerator` 刻意不生成静态初始化。
2. **`services()` 里的类字面量只能是接口，且接口必须在 base / 常驻模块**。接口若来自动态模块，
   方法体解析失败会拖垮整表。
3. 生成器里的 `ClassWriter.getCommonSuperClass` 被强制返回 `java/lang/Object`，因为实现类可能来自
   未安装的动态模块，ASM 不能真去加载它们。
4. `@ProviderFactory` 在 Kotlin 侧必须是 `companion object` 里的 `@ProviderFactory @JvmStatic fun`；
   Java 侧必须在 `static` 方法上，且一个类只能有一个。扫描器对这些情况直接抛 `GradleException`。
5. Kotlin 调用 Java 注解只接受命名参数：`@ServiceProvider(service = X::class)`。
6. 默认变体的 key 是空串（`ServiceLoader.DEFAULT_KEY == ""`），key 参数全程非空。

## 扫描器的边界（改 `SourceScanner` 前先读）

- 用正则 + 括号配对解析源码，不建 AST；`SourceText` 负责去注释、把字符串内容 mask 掉以免误匹配。
- 支持 `import a.b.*` 只在类型显式 import 或在本模块 `TypeIndex` 中唯一时解析；
  否则构建失败，要求显式 import / 全限定名。
- 不支持嵌套类、`typealias` 作为 service。
- `META-INF/services` 文件里的 provider 名必须形如 `com.foo.BarImpl`（点分全限定名），否则报错。
- 注解与 SPI 配置指向同一实现类时按整行去重；不同实现类抢同一 `(service, key)` 构建报错。

## 已知坑

- 改了插件源码却没重新 publish：demo 仍用 local-repo 里的旧版本，改动"不生效"。
- `ServiceLoader.initialize()` 直接 `new` 固定类名 `top.jayvin.android.spi.generated.ServiceConfiguration`；
  runtime 通过 `configuration-stub` 的 compileOnly 占位类编译，占位类不会被打包。该类缺失时只打日志并降级为空配置。
- R8：base 会对未安装模块的类报 missing class，需按模块名补 `-dontwarn`（见 `app/proguard-rules.pro`）。
- 环境：JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35；`local.properties` 含本机 SDK 路径。
- Gradle Daemon JVM 由 `gradle/gradle-daemon-jvm.properties`（`toolchainVersion=17`）固定；
  改版本用 `./gradlew updateDaemonJvm --jvm-version=<版本>`，不要手改该文件。
