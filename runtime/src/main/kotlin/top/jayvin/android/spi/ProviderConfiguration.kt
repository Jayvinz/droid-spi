package top.jayvin.android.spi

/**
 * 编译期生成的 provider 配置表要实现的接口。
 *
 * 对应 JDK SPI 的 provider-configuration file（`META-INF/services/<service FQN>`）：
 * 只是把"按文件名存放 provider 类名"换成"编译期生成一个固定全类名的 class"；
 * provider 实现仍由类字面量引用，运行时不按名字查找 provider。
 *
 * 实现类由 Gradle 插件用 ASM 直接生成字节码，固定全类名
 * `top.jayvin.android.spi.generated.ServiceConfiguration`。
 * runtime 以 compileOnly 占位类编译出直接 `new` 的字节码，运行时由 app 生成的同名类顶替。
 *
 * 约定：**provider 的类字面量只能出现在方法体内**，绝不能进 `<clinit>`。
 * 这样动态模块未安装时，只有该方法抛 [NoClassDefFoundError]，配置表自身不会被标记为 erroneous；
 * 模块装好后再调一次就能成功。
 */
interface ProviderConfiguration {

    /** 所有已注册的 service 接口。数组里**只能有接口**，且接口必须在 base / 常驻模块中。 */
    fun services(): Array<Class<*>>

    /** 与 [services] 一一对应的 key；空串表示默认变体。 */
    fun keys(): Array<String>

    /**
     * 只解析、不初始化：返回 provider 类，模块未安装时抛 [NoClassDefFoundError]。
     * 用于判断 provider 是否已就绪。
     */
    fun providerOf(service: Class<*>, key: String): Class<*>?

    /**
     * 解析并实例化（或走 `@ProviderFactory` 静态工厂），会触发 provider 的 `<clinit>`。
     * 用于真正取服务。
     */
    fun newProvider(service: Class<*>, key: String): Any?
}
