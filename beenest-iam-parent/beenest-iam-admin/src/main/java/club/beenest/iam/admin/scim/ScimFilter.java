package club.beenest.iam.admin.scim;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM 过滤表达式。
 * <p>只解析单条件表达式 {@code 属性 op "值"},支持 {@code eq}(等于)与 {@code co}(包含)。
 * 这是 HR 同步实际会用到的最小集合:按 {@code userName} / {@code externalId} / {@code displayName}
 * 精确查找,或按名称模糊查找。</p>
 * <p>多条件({@code and}/{@code or})与其它运算符按不支持处理并返回 400 {@code invalidFilter},
 * 而不是静默忽略——静默忽略会让"按条件更新"变成"更新了错误的记录"。</p>
 */
public record ScimFilter(String attribute, Operator operator, String value) {

    /** 支持的比较运算 */
    public enum Operator {
        /** 等于 */
        EQ,
        /** 包含 */
        CO
    }

    /** 表达式形态:属性名 + 运算符 + 双引号值 */
    private static final Pattern EXPRESSION =
            Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9._:]*)\\s+(eq|co)\\s+\"([^\"]*)\"\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 解析过滤表达式。
     *
     * @param filter 原始表达式,可为 null 或空
     * @return 解析结果;入参为空时返回 null
     * @throws ScimException 表达式非法或运算符不支持
     */
    public static ScimFilter parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        Matcher matcher = EXPRESSION.matcher(filter);
        if (!matcher.matches()) {
            throw ScimException.badRequest(ScimConstants.ERROR_INVALID_FILTER,
                    "不支持的过滤表达式: " + filter + "(仅支持 属性 eq|co \"值\")");
        }
        Operator operator = "eq".equals(matcher.group(2).toLowerCase(Locale.ROOT))
                ? Operator.EQ : Operator.CO;
        return new ScimFilter(matcher.group(1), operator, matcher.group(3));
    }

    /**
     * 判断属性名是否匹配。
     *
     * @param names 候选属性名,大小写不敏感
     * @return true 表示命中任一候选
     */
    public boolean matchesAttribute(String... names) {
        for (String name : names) {
            if (name.equalsIgnoreCase(attribute)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断给定值是否满足当前条件。
     *
     * @param candidate 待比较值,可为 null
     * @return 满足条件返回 true;候选值为 null 时返回 false
     */
    public boolean test(String candidate) {
        if (candidate == null) {
            return false;
        }
        return switch (operator) {
            case EQ -> candidate.equals(value);
            case CO -> candidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
        };
    }
}
