package club.beenest.iam.core.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 登录后必须完成的事项(Required Action)。
 * <p>管理员或系统给账号挂一个待办,用户下次登录时被引导完成,完成后自动摘除。
 * 与登录阶段的 MFA 门禁不同,待办不拦截登录,只限制会话可用范围。</p>
 */
public enum RequiredAction {

    /** 必须修改口令(管理员重置后或使用初始口令首次登录) */
    UPDATE_PASSWORD("更新口令"),

    /** 必须验证邮箱(未验证的邮箱不能用于找回口令) */
    VERIFY_EMAIL("验证邮箱"),

    /** 必须绑定二步验证(域策略或客户端要求) */
    CONFIGURE_TOTP("绑定二步验证"),

    /** 必须绑定 Passkey(域策略要求无口令登录或强认证场景) */
    CONFIGURE_PASSKEY("绑定 Passkey");

    /** 展示名 */
    private final String displayName;

    RequiredAction(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 按代码解析动作。
     * <p>未知代码返回 null,兼容存量脏值与高版本写入的值。</p>
     *
     * @param code 动作代码,忽略大小写与首尾空白
     * @return 匹配的动作;无法识别时返回 null
     */
    public static RequiredAction of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        for (RequiredAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        return null;
    }

    /**
     * 解析逗号分隔的动作串。
     * <p>无法识别的代码直接丢弃,避免低版本运行高版本写入的动作时卡住登录。</p>
     *
     * @param raw 逗号分隔的动作代码
     * @return 解析出的动作集合;入参为空时返回空集合
     */
    public static Set<RequiredAction> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<RequiredAction> result = new LinkedHashSet<>();
        for (String piece : raw.split(",")) {
            RequiredAction action = of(piece);
            if (action != null) {
                result.add(action);
            }
        }
        return result;
    }

    /**
     * 序列化为逗号分隔串。
     *
     * @param actions 动作集合
     * @return 逗号分隔的动作代码;集合为空时返回空串,与列默认值一致
     */
    public static String join(Set<RequiredAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (RequiredAction action : actions) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(action.name());
        }
        return sb.toString();
    }
}
