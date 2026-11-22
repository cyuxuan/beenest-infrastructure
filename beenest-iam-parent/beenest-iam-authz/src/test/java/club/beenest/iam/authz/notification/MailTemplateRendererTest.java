package club.beenest.iam.authz.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件模板渲染测试。
 * <p>关注两点:占位替换正确;变量被转义——邮件正文里可能出现用户可控内容
 * (昵称等),不转义就是一个可被注入的 HTML 片段。</p>
 */
class MailTemplateRendererTest {

    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @Test
    @DisplayName("内置模板存在且可渲染")
    void rendersBuiltInTemplates() {
        assertTrue(renderer.exists("password-reset"));
        assertTrue(renderer.exists("email-verification"));
        String html = renderer.render("password-reset", Map.of(
                "username", "张三", "resetUrl", "https://iam.example.com/reset-password?ticket=abc",
                "ttlMinutes", 30, "appName", "默认域", "year", 2026));
        assertNotNull(html);
        assertTrue(html.contains("张三"));
        assertTrue(html.contains("https://iam.example.com/reset-password?ticket=abc"));
        assertTrue(html.contains("30"));
        assertFalse(html.contains("{{"), "渲染后不应残留占位符");
    }

    @Test
    @DisplayName("变量值被 HTML 转义")
    void escapesVariables() {
        String html = renderer.render("password-reset", Map.of(
                "username", "<script>alert(1)</script>",
                "resetUrl", "https://iam.example.com/x?a=1&b=2",
                "ttlMinutes", 30, "appName", "默认域", "year", 2026));
        assertNotNull(html);
        assertFalse(html.contains("<script>alert(1)</script>"), "用户可控内容必须转义");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("a=1&amp;b=2"));
    }

    @Test
    @DisplayName("未赋值的占位符被清空,页面上不留 {{foo}}")
    void clearsUnassignedPlaceholders() {
        String html = renderer.render("password-reset", Map.of("username", "张三"));
        assertNotNull(html);
        assertFalse(html.contains("{{resetUrl}}"));
        assertFalse(html.contains("{{"));
    }

    @Test
    @DisplayName("模板不存在返回 null,由调用方记为投递失败")
    void missingTemplateReturnsNull() {
        assertNull(renderer.render("no-such-template", Map.of()));
        assertNull(renderer.render(null, Map.of()));
        assertFalse(renderer.exists("no-such-template"));
    }
}
