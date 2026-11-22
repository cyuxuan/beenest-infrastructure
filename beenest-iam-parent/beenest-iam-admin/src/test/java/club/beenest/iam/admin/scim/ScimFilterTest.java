package club.beenest.iam.admin.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCIM 过滤表达式解析测试。
 */
class ScimFilterTest {

    @Test
    @DisplayName("空表达式表示不过滤")
    void blankFilterMeansNoFilter() {
        assertThat(ScimFilter.parse(null)).isNull();
        assertThat(ScimFilter.parse("   ")).isNull();
    }

    @Test
    @DisplayName("解析 eq 条件")
    void parsesEquals() {
        ScimFilter filter = ScimFilter.parse("userName eq \"alice\"");
        assertThat(filter.attribute()).isEqualTo("userName");
        assertThat(filter.operator()).isEqualTo(ScimFilter.Operator.EQ);
        assertThat(filter.test("alice")).isTrue();
        assertThat(filter.test("bob")).isFalse();
    }

    @Test
    @DisplayName("解析 co 条件,大小写不敏感")
    void parsesContains() {
        ScimFilter filter = ScimFilter.parse("displayName co \"研发\"");
        assertThat(filter.operator()).isEqualTo(ScimFilter.Operator.CO);
        assertThat(filter.test("研发部")).isTrue();
        assertThat(filter.test("市场部")).isFalse();
    }

    @Test
    @DisplayName("属性名匹配大小写不敏感")
    void attributeMatchIsCaseInsensitive() {
        ScimFilter filter = ScimFilter.parse("userName eq \"alice\"");
        assertThat(filter.matchesAttribute("username")).isTrue();
        assertThat(filter.matchesAttribute("externalId")).isFalse();
    }

    @Test
    @DisplayName("空值不满足条件,避免把 null 当成命中")
    void nullCandidateNeverMatches() {
        assertThat(ScimFilter.parse("externalId eq \"HR-1\"").test(null)).isFalse();
    }

    @Test
    @DisplayName("多条件与未支持运算符显式报错,不静默忽略")
    void rejectsUnsupportedExpressions() {
        assertThatThrownBy(() -> ScimFilter.parse("userName eq \"a\" and active eq true"))
                .isInstanceOf(ScimException.class);
        assertThatThrownBy(() -> ScimFilter.parse("userName sw \"a\""))
                .isInstanceOf(ScimException.class);
        assertThatThrownBy(() -> ScimFilter.parse("userName eq alice"))
                .isInstanceOf(ScimException.class);
    }
}
