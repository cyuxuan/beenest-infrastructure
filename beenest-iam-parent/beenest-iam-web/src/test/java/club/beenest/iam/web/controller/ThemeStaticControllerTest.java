package club.beenest.iam.web.controller;

import club.beenest.iam.web.config.IamThemeProperties;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题静态资源控制器测试:路径穿越拒绝、已知资源可读、缺失资源 404。
 */
class ThemeStaticControllerTest {

    private final IamThemeProperties properties = new IamThemeProperties();
    private final IamThemeSelector selector = new IamThemeSelector(properties,
            new club.beenest.iam.common.theme.ThemeRegistry());
    private final ThemeStaticController controller = new ThemeStaticController(selector, properties);

    @Test
    void servesKnownResourceWithContentType() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/theme-static/css/iam.css");
        ResponseEntity<Resource> response = controller.serve(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("text/css;charset=UTF-8",
                response.getHeaders().getContentType().toString());
    }

    /**
     * 设计令牌文件:三端(登录/门户/控制台)换肤的唯一事实源,必须随主题目录一起下发。
     */
    @Test
    void servesDesignTokens() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/theme-static/css/tokens.css");
        ResponseEntity<Resource> response = controller.serve(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String css = new String(response.getBody().getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        // 规范词汇(不带端前缀)必须在内置主题里,否则三端令牌对不齐
        assertTrue(css.contains("--canvas:"), css);
        assertTrue(css.contains("--gold:"), css);
    }

    @Test
    void rejectsPathTraversal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/theme-static/../application.yml");
        ResponseEntity<Resource> response = controller.serve(request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void rejectsEncodedPathTraversal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/theme-static/%2e%2e/application.yml");
        ResponseEntity<Resource> response = controller.serve(request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void missingResourceReturns404() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/theme-static/css/no-such-file.css");
        ResponseEntity<Resource> response = controller.serve(request);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
