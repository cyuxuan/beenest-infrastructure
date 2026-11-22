package club.beenest.iam.authz.saml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动提交页渲染测试。
 * <p>页面里所有来自外部的值(SP 地址、RelayState、展示名)都会被写进 HTML,
 * 任何一个不转义就是一个反射型 XSS。这里逐项钉住。</p>
 */
class SamlPostBindingPageTest {

    private static final String ACS = "https://sp.example.com/acs";

    @Test
    void rendersFormPostingToAcs() {
        String html = SamlPostBindingPage.render(ACS, "PHNhbWw+", "relay-1", "示例应用");

        assertTrue(html.contains("method=\"post\""));
        assertTrue(html.contains("action=\"" + ACS + "\""));
        assertTrue(html.contains("name=\"SAMLResponse\" value=\"PHNhbWw+\""));
        assertTrue(html.contains("name=\"RelayState\" value=\"relay-1\""));
        assertTrue(html.contains("示例应用"));
        assertTrue(html.contains("document.forms[0].submit()"));
    }

    @Test
    void omitsRelayStateWhenBlank() {
        String html = SamlPostBindingPage.render(ACS, "PHNhbWw+", "  ", "示例应用");

        assertFalse(html.contains("RelayState"));
    }

    @Test
    void escapesValuesTakenFromRequest() {
        String html = SamlPostBindingPage.render(
                "https://sp.example.com/acs?a=1&b=2", "PHNhbWw+",
                "\"><script>alert(1)</script>", "<img src=x onerror=alert(1)>");

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&quot;"));
    }

    @Test
    void fallsBackToGenericNameWhenDisplayNameMissing() {
        String html = SamlPostBindingPage.render(ACS, "PHNhbWw+", null, "  ");

        assertTrue(html.contains("正在跳转到应用"));
    }
}
