package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信扫码登录提供方测试。
 * <p>微信的授权地址有三条硬约束,错了不会报错,只是微信不渲染二维码:
 * appid 必须在首位、redirect_uri 整体编码、地址以 #wechat_redirect 结尾。</p>
 */
class WeChatWebProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("授权地址满足微信的三条硬约束")
    void authorizationUrlMatchesWechatRequirements() {
        WeChatWebProvider provider = new WeChatWebProvider(
                config("https://open.weixin.qq.com/connect/qrconnect", null), new ObjectMapper());

        String url = provider.authorizationUrl(new AuthorizationRequest(
                "https://iam.example.com/broker/default/wx-web/callback", "state-1", Map.of()));

        assertTrue(url.startsWith("https://open.weixin.qq.com/connect/qrconnect?appid="),
                "appid 必须是第一个参数: " + url);
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fiam.example.com%2Fbroker%2Fdefault%2Fwx-web%2Fcallback"),
                "redirect_uri 必须整体编码: " + url);
        assertTrue(url.endsWith("#wechat_redirect"), "地址必须以 #wechat_redirect 结尾: " + url);
        assertTrue(url.contains("scope=snsapi_login"), "默认范围应为 snsapi_login: " + url);
        assertTrue(url.contains("state=state-1"), "必须携带 state: " + url);
    }

    @Test
    @DisplayName("回调换票:拿到 openid 与昵称")
    void exchangesCodeForIdentity() throws Exception {
        String baseUrl = startServer();
        WeChatWebProvider provider = new WeChatWebProvider(
                config("https://open.weixin.qq.com/connect/qrconnect", baseUrl), new ObjectMapper());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                new CallbackContext("auth-code", "state-1", "default",
                        "https://iam.example.com/broker/default/wx-web/callback", Map.of()));

        assertEquals("u-xyz", identity.getExternalId());
        assertEquals("张三", identity.getNickname());
        assertEquals("o-abc", identity.getAttributes().get("openid"));
    }

    @Test
    @DisplayName("回调缺少授权码:直接失败")
    void missingCodeFailsFast() {
        WeChatWebProvider provider = new WeChatWebProvider(
                config("https://open.weixin.qq.com/connect/qrconnect", null), new ObjectMapper());
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> provider.buildIdentityFromCallback(
                        new CallbackContext(null, "state-1", "default", null, Map.of())));
        assertTrue(error.getMessage().contains("授权码"), "错误信息要说明缺了什么: " + error.getMessage());
    }

    /**
     * 提供方配置。
     *
     * @param authorizationUrl 扫码授权地址
     * @param apiBaseUrl       微信接口基址;为空时用真实地址(仅用于校验授权地址拼装)
     */
    private IdentityProviderConfig config(String authorizationUrl, String apiBaseUrl) {
        String base = apiBaseUrl == null ? "https://api.weixin.qq.com" : apiBaseUrl;
        return new IdentityProviderConfig("wx-web", "wechat-web", "微信扫码登录", true,
                Map.of("clientId", "wx-open-id", "clientSecret", "wx-open-secret",
                        "authorizationUrl", authorizationUrl,
                        "tokenUrl", base + "/sns/oauth2/access_token",
                        "userInfoUrl", base + "/sns/userinfo"));
    }

    /** 内嵌微信接口:令牌端点与用户信息端点。 */
    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/oauth2/access_token", exchange -> {
            respond(exchange, "{\"access_token\":\"mock-access\",\"openid\":\"o-abc\","
                    + "\"unionid\":\"u-xyz\",\"expires_in\":7200}");
        });
        server.createContext("/sns/userinfo", exchange -> {
            respond(exchange, "{\"openid\":\"o-abc\",\"nickname\":\"张三\","
                    + "\"headimgurl\":\"https://img.example.com/a.png\"}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
