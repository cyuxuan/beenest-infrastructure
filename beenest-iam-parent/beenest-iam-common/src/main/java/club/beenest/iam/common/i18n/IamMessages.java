package club.beenest.iam.common.i18n;

import club.beenest.iam.common.result.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 文案解析入口。
 * <p>按错误码或资源键取当前请求语言的文案;资源缺失时回落错误码默认文案。
 * 文案源由 {@code IamI18nConfiguration} 在启动时注册。</p>
 */
public final class IamMessages {

    /** 错误码文案的资源键前缀,如 {@code error.20001} */
    private static final String ERROR_KEY_PREFIX = "error.";

    /** 容器启动时注册的解析器;未注册时为 null */
    private static volatile MessageSource messageSource;

    private IamMessages() {
    }

    /**
     * 注册文案源(容器启动时调用一次)。
     *
     * @param source Spring 文案源
     */
    public static void register(MessageSource source) {
        messageSource = source;
    }

    /**
     * 按错误码取当前请求语言的文案。
     *
     * @param code 错误码
     * @return 本地化文案;资源缺失或未注册文案源时返回错误码自带的默认文案
     */
    public static String message(ErrorCode code) {
        if (code == null) {
            return null;
        }
        MessageSource source = messageSource;
        if (source == null) {
            return code.getDefaultMessage();
        }
        return source.getMessage(ERROR_KEY_PREFIX + code.getCode(), null,
                code.getDefaultMessage(), LocaleContextHolder.getLocale());
    }

    /**
     * 按资源键取当前请求语言的文案。
     *
     * @param key  资源键
     * @param args 占位参数;可为空
     * @return 本地化文案;键不存在时返回键本身(便于在页面上直接看出漏配)
     */
    public static String message(String key, Object... args) {
        MessageSource source = messageSource;
        if (source == null) {
            return key;
        }
        return source.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /**
     * 按指定语言取资源键文案(用于不依赖请求上下文的场景,如导出文件)。
     *
     * @param key    资源键
     * @param locale 目标语言;为 null 时用当前请求语言
     * @param args   占位参数;可为空
     * @return 本地化文案;键不存在时返回键本身
     */
    public static String message(String key, Locale locale, Object... args) {
        MessageSource source = messageSource;
        if (source == null) {
            return key;
        }
        Locale target = locale == null ? LocaleContextHolder.getLocale() : locale;
        return source.getMessage(key, args, key, target);
    }
}
