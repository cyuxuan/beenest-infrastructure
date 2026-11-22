package club.beenest.iam.core.model;

/**
 * OIDC 主体标识({@code sub})的取值策略。
 * <p>{@code sub} 是应用侧识别用户的锚点,应在账号整个生命周期内保持不变。
 * 策略分两级:身份域给默认值,客户端可单独覆盖。</p>
 *
 * <ul>
 *   <li>{@link #LOGIN_NAME}:登录名,可读性好,但登录名变更后应用侧映射会失效;
 *       作为默认值兼容存量接入。</li>
 *   <li>{@link #USER_ID}:不可变用户主键,登录名变更不影响应用,
 *       展示名需应用另行读取 {@code preferred_username}。</li>
 * </ul>
 */
public enum SubjectMode {

    /** 登录名(存量兼容,默认) */
    LOGIN_NAME,

    /** 不可变用户主键 */
    USER_ID;

    /**
     * 解析文本,忽略大小写与空白。
     * <p>读取历史数据与外部配置统一走此处,无法识别时回落 {@link #LOGIN_NAME}。</p>
     *
     * @param value 策略文本
     * @return 匹配的策略;为空或无法识别时返回 LOGIN_NAME
     */
    public static SubjectMode parse(String value) {
        if (value == null || value.isBlank()) {
            return LOGIN_NAME;
        }
        String normalized = value.trim().toUpperCase();
        for (SubjectMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return LOGIN_NAME;
    }

    /**
     * 严格解析文本。
     *
     * @param value 策略文本
     * @return 匹配的策略;为空或无法识别时返回 null
     */
    public static SubjectMode parseStrict(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (SubjectMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * 数据库存储值。
     *
     * @return 大写枚举名
     */
    public String code() {
        return name();
    }
}
