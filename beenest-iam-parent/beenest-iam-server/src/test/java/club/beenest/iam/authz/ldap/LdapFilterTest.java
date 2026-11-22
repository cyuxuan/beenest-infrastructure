package club.beenest.iam.authz.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LDAP 过滤器转义测试:登录名是不可信输入,注入串能把"精确匹配"变成"任意条目"。
 */
class LdapFilterTest {

    @Test
    @DisplayName("通配符与括号被转义:)(uid=* 无法改变过滤器的匹配语义")
    void injectionCharactersAreEscaped() {
        assertEquals("alice", LdapFilter.escape("alice"));
        assertEquals("a\\2ab", LdapFilter.escape("a*b"));
        assertEquals("a\\28b", LdapFilter.escape("a(b"));
        assertEquals("a\\29b", LdapFilter.escape("a)b"));
        // 反斜杠必须最先处理,否则会二次转义后续转义序列里的反斜杠
        assertEquals("\\5c", LdapFilter.escape("\\"));
        assertEquals("\\5c2a", LdapFilter.escape("\\2a"));
    }

    @Test
    @DisplayName("登录名注入串被中和:过滤器整体仍是字面量比较")
    void injectedFilterStaysLiteral() {
        String malicious = "*)(uid=admin";
        assertEquals("(sAMAccountName=\\2a\\29\\28uid=admin)",
                LdapFilter.build("(sAMAccountName={0})", malicious));
    }

    @Test
    @DisplayName("正常登录名原样通过,不过度转义")
    void benignUsernamePassesThrough() {
        assertEquals("(sAMAccountName=zhang.san-01)",
                LdapFilter.build("(sAMAccountName={0})", "zhang.san-01"));
    }

    @Test
    @DisplayName("NUL 字节转义:目录解析器对 NUL 的处理不可移植")
    void nulByteIsEscaped() {
        assertEquals("a\\00b", LdapFilter.escape("a\u0000b"));
    }
}
