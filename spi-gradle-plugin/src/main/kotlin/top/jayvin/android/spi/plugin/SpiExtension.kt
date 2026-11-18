package top.jayvin.android.spi.plugin

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * 接入方在模块里配置：
 *
 * ```kotlin
 * spi {
 *     generatedPackage = "com.example.app.generated"
 * }
 * ```
 */
open class SpiExtension {
    /** 关掉后该模块既不扫描也不生成，用于排查构建问题 */
    var enabled: Boolean = true

    /**
     * 是否读取源码目录下的 `META-INF/services/<service>` 标准 SPI 配置
     * （典型位置 `src/main/resources/META-INF/services/`，也可放在 `src/main/java` / `src/main/kotlin` 下）。
     * 默认开启，用于按标准 SPI 语法接入第三方 provider —— 配置由本工程维护，实现类由第三方 jar / aar 提供。
     * 只读文本清单，不解压依赖产物、不扫 class。
     */
    var scanMetaInfServices: Boolean = true

    /** 生成类所在包。只有 app 模块的配置生效。 */
    var generatedPackage: String = "top.jayvin.android.spi.generated"

    /** 生成类名。manifest 里的 `<provider android:name>` 必须等于 `$generatedPackage.$generatedClassName` */
    var generatedClassName: String = "GeneratedServiceConfiguration"
}

/**
 * 挂在 root project 上的收集器：每个模块把自己 buildType 维度的扫描任务登记进来，
 * app 模块在 `projectsEvaluated` 时一次性取走。
 *
 * 用 buildType 而不是 variant 名作为 key，是因为 provider configuration 只需要按 buildType 聚合：
 * `debug` / `release` 各一份。代价是 flavor 独有的服务不会被收录，
 * 多 flavor 工程需要改成按 variant 名对齐。
 */
abstract class SpiRootExtension {
    /** buildType -> 各模块 scan 任务产出的清单文件 */
    val manifests: MutableMap<String, MutableList<Provider<RegularFile>>> = LinkedHashMap()
}

internal fun rootExtension(project: Project): SpiRootExtension {
    val root = project.rootProject
    return root.extensions.findByType(SpiRootExtension::class.java)
        ?: root.extensions.create("spiRoot", SpiRootExtension::class.java)
}
