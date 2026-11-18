# Android SPI ServiceLoader

面向 AAB 多模块工程的**编译期 provider 配置表**。命名与 `java.util.ServiceLoader` 的 SPI 词汇对齐
（service / provider / provider-configuration），base 模块不用 `Class.forName`、不用 APT/KSP、不解压第三方 jar，
就能在运行时拿到动态功能模块里的 provider；模块安装完成后配置表会自动重探一次。
provider 既可以来自 `@ServiceProvider` 注解，也可以来自源码目录下的标准 `META-INF/services/` 文本文件
（用于按 JDK SPI 约定接入第三方 jar / aar 提供的实现类）。

> 完整的设计背景、JVM 语义与方案对比见 [docs/android-service-discovery-design.md](docs/android-service-discovery-design.md)。

```
扫描期（每个模块）                              聚合期（app）                    运行期
@ServiceProvider ─┐                                                        Provider.onCreate
                  ├─scan─▶ 清单 ─merge─▶ GeneratedServiceConfiguration.class ─dex─▶    │
META-INF/services ─┘                                                       SplitInstall.INSTALLED ──▶ refresh()
```

## 与 JDK SPI 的术语对照

| JDK `java.util.ServiceLoader` | 本工程 | 说明 |
|---|---|---|
| service | `HomeService` 接口 | 下沉在 base 的契约 |
| service provider | `HomeServiceImpl` | 各 feature 模块里的实现 |
| provider-configuration file | `GeneratedServiceConfiguration`（主）／源码目录 `META-INF/services/`（兼容） | 编译期生成的 class 为主；标准 SPI 文本作为补充输入 |
| `ServiceLoader.load(service)` | `ServiceLoader.load(service, key)` | 多了一个业务变体 key |
| `ServiceLoader.Provider.type()/get()` | `ServiceLoader.Provider.type()/get()/state()` | 多了 provider 状态 |
| `ServiceLoader.stream()` | `ServiceLoader.stream()` | 语义一致，Kotlin 侧返回 `Sequence` |
| — | `ServiceLoader.refresh()` | JDK 独有：classpath 不变，不需要；AAB 需要 |

主要差异：`load()` 直接返回实例而不是返回 loader 对象；服务不可用时返回 `null` 而不是抛异常；
多了 `key` 与 `ProviderState` 来处理动态分发的中间态。

## 目录

```
android-spi/
├── spi-annotation/     @ServiceProvider / @ProviderFactory（纯 Java 注解，零依赖）
├── spi-runtime/        ServiceLoader 门面 + provider 状态机 + 安装监听（Android Library）
├── spi-gradle-plugin/  扫描与生成（Gradle Plugin，ASM 生成 class）
├── local-repo/         publish 产物（本地 maven 仓库）
└── sample/             多模块示例工程（独立 build，消费 local-repo）
    ├── app/            接入方：manifest 一行 <provider> + 一个 spi{} 配置
    ├── core-api/       service 接口层（所有 @ServiceProvider 的 service 都在这里）
    ├── core-common/    普通常驻 library
    ├── feature-home/   HomeService 默认 provider + vip 变体
    ├── feature-account/ @ProviderFactory 私有构造 + guest 变体
    └── feature_camera/ on-demand 动态模块：CameraService provider + 独立 Activity
```

## 快速开始

```bash
# 1. 发布三个模块到本地 maven（local-repo/）
./gradlew publish

# 2. 构建示例（独立工程，通过 local-repo 消费插件）
cd sample
../gradlew :app:assembleDebug      # APK
../gradlew :app:bundleDebug        # AAB（含 feature_camera 分包）
```

跑测试：

```bash
./gradlew :spi-runtime:testDebugUnitTest
```

验证生成物：

```bash
# 配置类字节码：只有方法体引用实现类，没有 <clinit>
javap -p -c sample/app/build/intermediates/classes/debug/DebugGenerateServiceConfiguration/com/example/spi/generated/GeneratedServiceConfiguration.class

# 确认 provider 进了 APK、配置类进了 base dex
"D:/Android/Sdk/build-tools/35.0.0/aapt2.exe" dump xmltree sample/app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
```

## 接入三步

**1. 依赖与插件**

```kotlin
plugins {
    id("com.android.application")     // library / dynamic-feature 同理
    id("org.jetbrains.kotlin.android")
    id("com.example.spi")
}

dependencies {
    implementation("com.example.spi:spi-annotation:1.0.0")
    implementation("com.example.spi:spi-runtime:1.0.0")
}
```

**2. manifest 声明初始化入口（唯一的手写胶水）**

```xml
<provider
    android:name="com.example.spi.generated.GeneratedServiceConfiguration"
    android:authorities="${applicationId}.spi-configuration"
    android:exported="false" />
```

系统会在 `Application.onCreate` **之前**实例化它 —— 所以业务代码第一次调
`ServiceLoader.load()` 时配置表已经就绪。`android:name` 必须等于
`spi { generatedPackage + generatedClassName }`（默认值就是上面这个）。

**3. 声明与调用**

注解是**纯 Java 注解**，所以纯 Java 项目和 Kotlin 项目都能用；但 Kotlin 侧对 Java 注解
**只接受命名参数**，必须写 `service =`：

```kotlin
@ServiceProvider(service = HomeService::class)
class HomeServiceImpl : HomeService

@ServiceProvider(service = HomeService::class, key = "vip")
class VipHomeServiceImpl : HomeService {
    // 构造器有参 / 私有时，用静态工厂
    companion object {
        @ProviderFactory @JvmStatic
        fun create(): VipHomeServiceImpl = VipHomeServiceImpl()
    }
}
```

纯 Java 项目里位置参数和命名参数都行：

```java
@ServiceProvider(HomeService.class)
public class HomeServiceImpl implements HomeService { }

@ServiceProvider(service = HomeService.class, key = "vip")
public class VipHomeServiceImpl implements HomeService { }
```

```kotlin
val home = ServiceLoader.load<HomeService>()                    // Kotlin 专属 reified 写法
val vip  = ServiceLoader.load(HomeService::class.java, "vip")   // key 变体
val cls  = ServiceLoader.loadClass(HomeService::class.java)     // 只要 Class
val all  = ServiceLoader.loadAll(AccountService::class.java)    // key -> 实例

// JDK 风格的 Provider 句柄
val first = ServiceLoader.findFirst(HomeService::class.java)
ServiceLoader.stream(HomeService::class.java).forEach { provider ->
    Log.d("spi", "${provider.type().name} / ${provider.state()}")
}
```

Java 侧所有公开方法都有 `@JvmStatic`，但不支持 reified，用 Class 字面量；
`Sequence` 在 Java 里不好用，改用 `providers()` 拿 List：

```java
HomeService home = ServiceLoader.load(HomeService.class);
HomeService vip  = ServiceLoader.load(HomeService.class, "vip");
for (Provider<HomeService> p : ServiceLoader.providers(HomeService.class)) {
    Log.d("spi", p.type().getName() + " / " + p.state());
}
```

所有 `load*` 都返回 `null` 而不抛异常：服务不可用（动态模块没装）是常态。

## 兼容标准 SPI 配置（META-INF/services）

第三方 jar / aar 里的实现类往往不会标我们的 `@ServiceProvider`，但通常按 JDK SPI 约定声明了
`META-INF/services/<service 全限定名>`。为了避免改第三方源码，本框架**读源码目录下的同名文本文件**
作为等价输入：

```
your-module/src/main/resources/META-INF/services/com.example.core.api.LegacyService
```

文件内容与 `java.util.ServiceLoader` 完全一致 —— 一行一个实现类全限定名，`#` 起注释，空行忽略：

```text
# 模拟第三方库按 JDK SPI 约定声明 provider
com.example.core.common.LegacyServiceImpl
com.thirdparty.SomeOtherImpl   # 多个也支持
```

要点：

- **配置在本工程源码里维护，实现类由第三方 jar / aar 提供**。框架只扫描源码目录的文本文件，
  **不解压、不扫依赖产物里的 class**（延续"零第三方 jar 扫描"的约束）；
- 扫描路径：`src/main/resources/META-INF/services/**`，也接受 `src/main/java` / `src/main/kotlin`
  下任意深度的 `META-INF/services/` 目录；由 `spi { scanMetaInfServices = true }` 控制（默认开启）；
- 标准 SPI 配置没有 `key` / `factory` 概念，统一映射为 `key = ""`、走无参构造 ——
  因此**实现类必须有公开无参构造**；需要 key 变体或私有构造 + 工厂时，仍用 `@ServiceProvider` / `@ProviderFactory`；
- 每条配置进入清单后与注解声明走完全相同的聚合与字节码生成流程，运行时无差别；
- 两种声明指向**同一 provider 实现类**时按整行去重，不算冲突；不同实现类抢同一 `(service, key="")` 仍会构建报错。

> 该模块必须 apply `com.example.spi` 插件，否则没有 `xxScanProviders` 任务，配置不会被扫描。
> `sample/core-common` 就是只有 SPI 配置、没有注解的最小示例。

## 动态模块安装后发生了什么

1. 进程内任意位置调用 Play 的 `startInstall()`；
2. 框架注册的 `SplitInstallStateUpdatedListener` 收到 `INSTALLED`；
3. `ProviderRegistry.refresh()` 把该模块上次探测为 `MISSING` 的条目重探一次 → `READY`；
4. 之后再 `load()` 就能拿到实例。

`refresh()` 的三条语义：

| 状态 | refresh 行为 |
|---|---|
| `READY` | 原样保留，不重新解析 |
| `MISSING` | 重探：`providerOf` 抛 `NoClassDefFoundError` 就继续 MISSING |
| `DEAD` | 原样保留，不再尝试（`<clinit>` 失败不可逆） |

不需要额外处理的两个场景：冷启动（模块早装好，初始化首扫即 READY）、进程被杀后重启（同上）。

## 为什么可以「零反射」

- **配置类由 Android 框架实例化**：`<provider android:name>` 是 manifest 声明，不是我们的代码去 `Class.forName`。
- **provider 用类字面量引用**：生成字节码里的 `ldc com/example/...Impl` / `new`，R8 能看到引用自动保留。
- **关键约束**：这些字面量**只能出现在方法体里**，绝不能进 `<clinit>`。
  否则动态模块未安装时配置类自己被标记 erroneous（`Could not initialize class`），
  整个表报废且不可恢复。所以生成器刻意不生成 `<clinit>`。

## 生成的东西长什么样

```java
public final class GeneratedServiceConfiguration
        extends ServiceLoaderInitializer implements ProviderConfiguration {
    public GeneratedServiceConfiguration() { super(); }
    protected ProviderConfiguration createConfiguration() { return this; }

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

`services()` 里只有接口 —— 接口必须下沉到 base，否则方法体一旦解析不到就整表失效（唯一的硬约束）。

## 构建期校验

扫描器宁可构建失败，也不生成错误的表：

| 违反 | 结果 |
|---|---|
| 同一 `(service, key)` 两个 provider | `GenerateServiceConfigurationTask` 报错，列出两个实现类 |
| `@ProviderFactory` 非静态 / 缺 `@JvmStatic` | `ScanProvidersTask` 报错，给出正确写法 |
| 一个类里多个 `@ProviderFactory` | 报错 |
| `service` 写成非法表达式（非 `X::class` / `X.class`） | 报错 |
| 存在 `import a.b.*` 且无法确定全限定名 | 报错，要求显式 import 或写全名 |
| `META-INF/services/` 文件里的 provider 名非法（非 `com.foo.Bar` 形式） | `ScanProvidersTask` 报错，给出文件名与行内容 |
| 注解与 SPI 配置里不同实现类抢同一 `(service, key)` | `GenerateServiceConfigurationTask` 报错，列出两个实现类 |

## 已知限制

- **接口必须在 base**：`services()` 里的类字面量若来自动态模块，配置表会整表失效。
- **扫描的是 `src/main/java` + `src/main/kotlin` + `src/main/resources`（SPI 配置）**：flavor 独有目录（`src/debug/`）与自定义 sourceSet 未覆盖；`META-INF/services` 也只认源码目录，不读依赖产物。
- **按 buildType 聚合**：`debug`/`release` 各一份，flavor 共享；多 flavor 需要改成按 variant 对齐。
- **不支持嵌套类 / `typealias` 作为 service**：扫描器按顶层类型解析。
- **非 Play 渠道**：`SplitInstallManagerFactory.create` 失败时框架退化为纯静态配置表（所有 provider 本来就打进包里，天然 READY），行为正确。
- **R8**：base 会把未安装模块的类报 missing class，按模块名补 `-dontwarn`（`sample/app/proguard-rules.pro` 有示例）。

## 环境

- JDK 17、Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21、compileSdk 35
- 国内环境如需代理加速依赖下载，取消 `gradle.properties`（根目录与 `sample/` 各一份）里 clash 代理段的注释并按需改端口；默认已注释，不影响其他环境构建
- Gradle Wrapper 使用官方分发地址，首次构建需联网下载 Gradle 8.9
