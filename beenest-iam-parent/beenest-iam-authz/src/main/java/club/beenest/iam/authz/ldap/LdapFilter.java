package club.beenest.iam.authz.ldap;

/**
 * LDAP 过滤器值转义(RFC 4515)。
 * <p>登录名来自不受控的表单,拼进 {@code (sAMAccountName={0})} 之类的过滤器时,
 * {@code *)(uid=*} 等注入串会把精确匹配变成通配匹配,直接绑到目录中任意账号(如
 * 管理员 DN)上。</p>
 * <p>所有进入过滤器的外部值先经 {@link #escape(String)} 处理。</p>
 */
public final class LdapFilter {

    private LdapFilter() {
    }

    /**
     * 按 RFC 4515 转义过滤器特殊字符。
     *
     * @param value 外部输入(登录名、属性值)
     * @return 转义后的安全字面量;null 返回空串
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                // 反斜杠必须最先处理:后续转义序列自身含反斜杠,后处理会二次转义
                case '\\' -> builder.append("\\5c");
                case '*' -> builder.append("\\2a");
                case '(' -> builder.append("\\28");
                case ')' -> builder.append("\\29");
                case '\u0000' -> builder.append("\\00");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * 用转义后的登录名填充过滤器模板中的 {@code {0}} 占位。
     *
     * @param template 过滤器模板,如 {@code (sAMAccountName={0})}
     * @param username 外部登录名
     * @return 可直接交给目录的过滤器
     */
    public static String build(String template, String username) {
        String safe = template == null ? "" : template;
        return safe.replace("{0}", escape(username));
    }
}
