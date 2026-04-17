package club.beenest.payment.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    String key();

    int limit() default 10;

    int period() default 60;

    LimitType limitType() default LimitType.USER;

    enum LimitType {
        USER,
        IP,
        GLOBAL
    }
}
