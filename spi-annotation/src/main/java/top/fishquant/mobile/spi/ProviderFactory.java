package top.fishquant.mobile.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在<b>静态无参方法</b>上，作为 provider 实例的构造入口，
 * 用来绕开"实现类必须有无参构造"的限制。
 *
 * <pre>{@code
 * @ServiceProvider(ConfigService.class)
 * public class ConfigServiceImpl implements ConfigService {
 *     private final Application app;
 *
 *     private ConfigServiceImpl(Application app) { this.app = app; }
 *
 *     @ProviderFactory
 *     public static ConfigServiceImpl create() {
 *         return new ConfigServiceImpl(AppHolder.app());
 *     }
 * }
 * }</pre>
 *
 * <p>三条硬性约束（违反会在构建期报错）：
 * <ol>
 *   <li>必须是静态方法；Kotlin 侧需要 companion object + &#64;JvmStatic；</li>
 *   <li>必须无参；</li>
 *   <li>返回类型必须是所在类自身，这样生成的 INVOKESTATIC 描述符可以静态拼接。</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ProviderFactory {
}
