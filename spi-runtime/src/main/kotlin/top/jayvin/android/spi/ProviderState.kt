package top.jayvin.android.spi

/**
 * 一个 provider 在本进程内的可用状态。
 *
 * JDK SPI 没有这个概念——它的 classpath 启动后不再变化。
 * AAB 动态分发会让 provider 在运行中才变得可见，因此需要模型化"还没装好"这个中间态。
 */
enum class ProviderState {
    /** provider 类当前不可见（动态模块未安装），可重试。 */
    MISSING,

    /** provider 类可解析。 */
    READY,

    /** provider 的 `<clinit>` 失败，JVM 已把它标记为 erroneous，本进程内不可逆。 */
    DEAD,
}
