package club.beenest.iam.web.config;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 模板格式化工具。
 * <p>由 {@link IamPageModelAdvice} 以 {@code fmt} 之名注入模型,页面通过
 * {@code ${fmt.dateTime(...)}} 调用。之所以不依赖 Thymeleaf 的 {@code #temporals}:
 * 那需要额外的 java8time 扩展,而这里只有一种格式需求,自己格式化比多引一个依赖更划算。</p>
 */
@Component
public class IamTemplateFormatter {

    /** 页面展示用的时间格式(不含秒,足够定位"什么时候") */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 格式化时间。
     *
     * @param value 本地时间,可为 null
     * @return {@code yyyy-MM-dd HH:mm} 文本;入参为 null 时返回破折号
     */
    public String dateTime(LocalDateTime value) {
        return value == null ? "—" : DATE_TIME.format(value);
    }

    /**
     * 截断过长的文本。
     *
     * @param value     原始文本,可为 null
     * @param maxLength 保留的最大字符数
     * @return 截断后的文本(超出部分以省略号结尾);入参为 null 时返回空串
     */
    public String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }
}
