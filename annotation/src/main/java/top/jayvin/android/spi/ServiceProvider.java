package top.jayvin.android.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a service provider (the implementation side of a service).
 *
 * <p>Naming follows JDK SPI: {@link #service()} is the interface and the provider is the
 * implementation. Retention is CLASS — scanning happens at the source stage, so the
 * annotation never enters runtime and works equally for pure Java and pure Kotlin projects.
 *
 * <pre>{@code
 * @ServiceProvider(HomeService.class)
 * public class HomeServiceImpl implements HomeService { }
 *
 * @ServiceProvider(service = HomeService.class, key = "vip")
 * public class VipHomeServiceImpl implements HomeService { }
 * }</pre>
 *
 * <p>Constraints:
 * <ul>
 *   <li>service must be an interface and must live in base / resident library modules;</li>
 *   <li>a non-empty {@link #key()} is unique per service globally; duplicates fail the build;</li>
 *   <li>the empty key (the default implementation) may have several providers: {@code load}
 *       returns the first available one, while {@code providers} / {@code stream} enumerate all
 *       of them.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface ServiceProvider {

    /**
     * The service interface.
     *
     * @return the service interface; must be a class literal {@code Xxx.class} or Kotlin's
     *     {@code Xxx::class}
     */
    Class<?> service();

    /**
     * Variant label within the same interface.
     *
     * @return the variant label, such as "vip" / "lite"; empty for the default implementation
     */
    String key() default "";
}
