package top.jayvin.android.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个 service provider（服务提供者实现）。
 *
 * <p>命名对齐 JDK SPI：{@link #service()} 是接口，provider 是实现。
 * 保留级别为 CLASS —— 扫描发生在源码阶段，注解本身不进运行时，
 * 因此纯 Java 项目、纯 Kotlin 项目都一样可用。
 *
 * <pre>{@code
 * @ServiceProvider(HomeService.class)
 * public class HomeServiceImpl implements HomeService { }
 *
 * @ServiceProvider(service = HomeService.class, key = "vip")
 * public class VipHomeServiceImpl implements HomeService { }
 * }</pre>
 *
 * <p>约束：
 * <ul>
 *   <li>service 必须是接口，且必须位于 base / 常驻 library 模块中；</li>
 *   <li>同一 (service, key) 全局只能有一个 provider，重复会在构建期报错；</li>
 *   <li>同一接口最多只能有一个空 key 的 provider（默认实现）。</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ServiceProvider {

    /** service 接口，必须写成类字面量 {@code Xxx.class} 或 Kotlin 的 {@code Xxx::class}。 */
    Class<?> service();

    /** 同一接口下的变体标签，如 "vip" / "lite"；默认实现留空。 */
    String key() default "";
}
