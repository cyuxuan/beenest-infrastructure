package club.beenest.iam.provider.wechat;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信小程序提供方测试。
 * <p>换票是纯外部调用,最容易错在几处:把 errcode 当成身份解析、unionid 缺失时没有回落到
 * openid、把客户端上送的手机号当作已核验值。这里用内嵌 HTTP 服务把三种情况都钉住。</p>
 * <p>手机号链路还依赖接口调用凭据:凭据缓存是 JVM 级的,测试需确认同基址多次登录只取一次,
 * 且凭据失效时清缓存重试。</p>
 */
class WeChatMiniProgramProviderTest {

    private FakeWechat wechat;

    @AfterEach
    void stopServer() {
        if (wechat != null) {
            wechat.stop();
        }
    }

    @Test
    @DisplayName("换票成功:外部标识取 unionid,openid 落进属性")
    void exchangesCodeForIdentity() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\",\"session_key\":\"sk\",\"unionid\":\"u-xyz\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(credentialContext("js-code"));

        assertEquals("u-xyz", identity.getExternalId(), "有 unionid 时以外部统一标识为准");
        assertEquals("o-abc", identity.getAttributes().get("openid"));
        assertEquals("u-xyz", identity.getAttributes().get("unionid"));
        assertNull(identity.getAttributes().get("session_key"), "会话密钥不落库");
        assertNull(identity.getVerifiedPhone(), "未提交 phoneCode 时不应有已验证手机号");
    }

    @Test
    @DisplayName("unionid 缺失:外部标识回落到 openid")
    void fallsBackToOpenId() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\",\"session_key\":\"sk\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(credentialContext("js-code"));

        assertEquals("o-abc", identity.getExternalId());
    }

    @Test
    @DisplayName("提交的 code 无效:按认证失败回给客户端,不报成上游故障")
    void invalidCodeFailsAsCredentialError() throws Exception {
        wechat = new FakeWechat("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.buildIdentityFromCallback(credentialContext("js-code")));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("40029"), "错误码应当出现在错误信息里: " + error.getMessage());
    }

    @Test
    @DisplayName("平台配置类错误:仍按上游不可用处理")
    void configErrorStaysUpstreamFailure() throws Exception {
        wechat = new FakeWechat("{\"errcode\":40013,\"errmsg\":\"invalid appid\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.buildIdentityFromCallback(credentialContext("js-code")));
        assertTrue(error.getMessage().contains("40013"), "错误码应当出现在错误信息里: " + error.getMessage());
    }

    @Test
    @DisplayName("凭据缺失:直接失败")
    void missingCredentialFailsFast() {
        WeChatMiniProgramProvider provider = provider("http://127.0.0.1:1");
        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.buildIdentityFromCallback(
                        new CallbackContext(null, null, "default", null, Map.of())));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
    }

    @Test
    @DisplayName("手机号登录:手机号由服务端换取,写入已验证手机号")
    void phoneNumberObtainedThroughServer() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\",\"session_key\":\"sk\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                phoneCredentialContext("js-code", "phone-code"));

        assertEquals("o-abc", identity.getExternalId());
        assertEquals("13800138000", identity.getVerifiedPhone());
        assertEquals(1, wechat.phoneHits(), "手机号应当只换取一次");
    }

    @Test
    @DisplayName("缺少手机号授权:默认拒绝,且不发起换票请求")
    void phoneCodeRequiredByDefault() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl(), true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.buildIdentityFromCallback(credentialContext("js-code")));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("phoneCode"), "错误信息应点明缺少 phoneCode: " + error.getMessage());
        assertEquals(0, wechat.sessionHits(), "缺少手机号授权时不应调用微信接口");
    }

    @Test
    @DisplayName("手机号接口报错:登录失败,错误码透出")
    void phoneApiErrorFails() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\"}");
        wechat.enqueuePhoneResponse("{\"errcode\":40003,\"errmsg\":\"invalid openid\"}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.buildIdentityFromCallback(phoneCredentialContext("js-code", "phone-code")));

        assertTrue(error.getMessage().contains("40003"), "错误码应当出现在错误信息里: " + error.getMessage());
    }

    @Test
    @DisplayName("接口凭据跨实例复用:同一进程内多次登录只取一次")
    void tokenFetchedOnceAcrossInstances() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\"}");

        BrokeredIdentityContext first = provider(wechat.baseUrl())
                .buildIdentityFromCallback(phoneCredentialContext("js-code", "phone-code"));
        BrokeredIdentityContext second = provider(wechat.baseUrl())
                .buildIdentityFromCallback(phoneCredentialContext("js-code", "phone-code"));

        assertEquals("13800138000", first.getVerifiedPhone());
        assertEquals("13800138000", second.getVerifiedPhone());
        assertEquals(1, wechat.tokenHits(), "工厂每次登录都新建提供方实例,凭据须在进程内复用");
    }

    @Test
    @DisplayName("接口凭据失效:清缓存重取一次后成功")
    void tokenRefreshedOnceWhenInvalid() throws Exception {
        wechat = new FakeWechat("{\"openid\":\"o-abc\"}");
        wechat.enqueuePhoneResponse("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
        wechat.enqueuePhoneResponse("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\"}}");
        WeChatMiniProgramProvider provider = provider(wechat.baseUrl());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                phoneCredentialContext("js-code", "phone-code"));

        assertEquals("13800138000", identity.getVerifiedPhone());
        assertEquals(2, wechat.tokenHits(), "凭据失效后应重新获取一次");
        assertEquals(2, wechat.phoneHits(), "手机号换取在重取凭据后应重试一次");
    }

    /**
     * 凭据上下文:键名由提供方自己定义。
     *
     * @param jsCode 小程序登录 code
     * @return 只含 jsCode 的凭据上下文
     */
    private static CallbackContext credentialContext(String jsCode) {
        return new CallbackContext(null, null, "default", null,
                Map.of(WeChatMiniProgramProvider.CREDENTIAL_JS_CODE, jsCode));
    }

    /**
     * 含手机号授权的凭据上下文。
     *
     * @param jsCode    小程序登录 code
     * @param phoneCode 手机号授权 code
     * @return 同时含 jsCode 与 phoneCode 的凭据上下文
     */
    private static CallbackContext phoneCredentialContext(String jsCode, String phoneCode) {
        return new CallbackContext(null, null, "default", null,
                Map.of(WeChatMiniProgramProvider.CREDENTIAL_JS_CODE, jsCode,
                        WeChatMiniProgramProvider.CREDENTIAL_PHONE_CODE, phoneCode));
    }

    /**
     * 构造提供方,关闭手机号必填。
     *
     * @param apiBaseUrl 内嵌微信接口基址
     * @return 提供方实例
     */
    private static WeChatMiniProgramProvider provider(String apiBaseUrl) {
        return provider(apiBaseUrl, false);
    }

    /**
     * 构造提供方。
     *
     * @param apiBaseUrl         内嵌微信接口基址
     * @param phoneLoginRequired true 写入显式开启,false 关闭;两者都写配置项
     * @return 提供方实例
     */
    private static WeChatMiniProgramProvider provider(String apiBaseUrl, boolean phoneLoginRequired) {
        Map<String, String> properties = new HashMap<>();
        properties.put("clientId", "wx-app-id");
        properties.put("clientSecret", "wx-app-secret");
        properties.put("apiBaseUrl", apiBaseUrl);
        properties.put("phoneLoginRequired", String.valueOf(phoneLoginRequired));
        IdentityProviderConfig config = new IdentityProviderConfig("wx-mini", "wechat-mini", "微信小程序", true,
                properties);
        return new WeChatMiniProgramProvider(config, new ObjectMapper());
    }

    /** 内嵌微信接口:三组端点各自返回预置 JSON,并统计调用次数。 */
    private static final class FakeWechat {

        private final HttpServer server;
        private final AtomicInteger sessionHits = new AtomicInteger();
        private final AtomicInteger tokenHits = new AtomicInteger();
        private final AtomicInteger phoneHits = new AtomicInteger();
        private final Deque<String> phoneResponses = new ConcurrentLinkedDeque<>();

        /**
         * 启动内嵌服务。
         *
         * @param sessionJson code2session 固定响应
         * @throws IOException 端口绑定失败
         */
        private FakeWechat(String sessionJson) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sns/jscode2session",
                    exchange -> respond(exchange, sessionJson, sessionHits));
            server.createContext("/cgi-bin/stable_token",
                    exchange -> respond(exchange, "{\"access_token\":\"token-1\",\"expires_in\":7200}", tokenHits));
            server.createContext("/wxa/business/getuserphonenumber", exchange -> {
                String body = phoneResponses.isEmpty()
                        ? "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800138000\"}}"
                        : phoneResponses.poll();
                respond(exchange, body, phoneHits);
            });
            server.start();
        }

        /**
         * 预置一次手机号接口响应,按入队顺序消费。
         *
         * @param body 完整响应 JSON
         */
        private void enqueuePhoneResponse(String body) {
            phoneResponses.add(body);
        }

        /**
         * 接口基址。
         *
         * @return 不带结尾斜杠的基址
         */
        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /**
         * 关闭内嵌服务。
         */
        private void stop() {
            server.stop(0);
        }

        private int sessionHits() {
            return sessionHits.get();
        }

        private int tokenHits() {
            return tokenHits.get();
        }

        private int phoneHits() {
            return phoneHits.get();
        }

        /**
         * 固定返回一段 JSON,并记录调用次数。
         *
         * @param exchange 请求上下文
         * @param body     响应 JSON
         * @param counter  调用计数
         * @throws IOException 响应写出失败
         */
        private static void respond(HttpExchange exchange, String body, AtomicInteger counter) throws IOException {
            counter.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
