package top.jayvin.android.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a <b>static, no-argument method</b> as the construction entry point for a provider,
 * bypassing the "implementation must have a no-arg constructor" restriction.
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
 * <p>Requirements:
 * <ol>
 *   <li>Must be a static method; on Kotlin it needs a companion object + &#64;JvmStatic.
 *       The scanner enforces this form and allows at most one factory per class;</li>
 *   <li>Must take no arguments;</li>
 *   <li>The return type must be the enclosing class itself, so the generated INVOKESTATIC
 *       descriptor can be assembled statically.</li>
 * </ol>
 *
 * <p>Requirements 2 and 3 are not checked at build time; a violation produces a descriptor
 * mismatch that fails as a NoSuchMethodError when the provider is first loaded.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ProviderFactory {
}
