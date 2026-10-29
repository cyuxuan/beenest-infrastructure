package club.beenest.payment.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IAM 令牌解码器集成测试。
 * <p>
 * 用一个本地 HTTP 服务模拟 IAM 的发现文档与 JWKS，验证 {@link PaymentSecurityConfig#iamJwtDecoder}
 * 组装的校验链：验签 + issuer + 受众 + 令牌类型。
 */
class IamJwtDecoderTest {

    private static final String AUDIENCE = "urn:iam:app:payment";
    private static final String KEY_ID = "test-key";

    private static HttpServer server;
    private static String issuer;
    private static KeyPair keyPair;

    @BeforeAll
    static void startFakeIam() throws Exception {
        // 1. 生成 RSA 密钥对，用于签名与 JWKS 发布
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + KEY_ID + "\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"n\":\"" + base64Url(unsignedBytes(publicKey.getModulus())) + "\","
                + "\"e\":\"" + base64Url(unsignedBytes(publicKey.getPublicExponent())) + "\"}]}";

        // 2. 启动本地服务，暴露发现文档与 JWKS
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/beenest";

        String discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/oauth2/jwks\"}";
        server.createContext("/realms/beenest/.well-known/openid-configuration",
                exchange -> respond(exchange, discovery));
        server.createContext("/realms/beenest/oauth2/jwks",
                exchange -> respond(exchange, jwks));
        server.start();
    }

    @AfterAll
    static void stopFakeIam() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 合法访问令牌应通过验签与全部声明校验。
     */
    @Test
    void shouldAcceptValidAccessToken() throws Exception {
        String token = sign(claims("access", AUDIENCE));

        Jwt jwt = buildDecoder().decode(token);

        assertEquals(issuer, jwt.getClaimAsString("iss"));
        assertEquals(AUDIENCE, jwt.getAudience().get(0));
        assertEquals("1024", jwt.getClaimAsString("uid"));
    }

    /**
     * 受众不符（同域其他应用的令牌）必须被拒绝。
     */
    @Test
    void shouldRejectTokenWithForeignAudience() throws Exception {
        String token = sign(claims("access", "urn:iam:app:drone"));

        assertThrows(JwtValidationException.class, () -> buildDecoder().decode(token));
    }

    /**
     * ID Token 不能当作 Bearer 令牌使用。
     */
    @Test
    void shouldRejectIdToken() throws Exception {
        String token = sign(claims("id", AUDIENCE));

        JwtValidationException error = assertThrows(JwtValidationException.class,
                () -> buildDecoder().decode(token));
        assertTrue(error.getMessage().contains("token_type"), "错误信息应指出令牌类型不合法");
    }

    /**
     * issuer 不符的令牌必须被拒绝。
     */
    @Test
    void shouldRejectTokenFromOtherIssuer() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("http://evil.example.com/realms/beenest")
                .subject("1024")
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("uid", 1024)
                .claim("token_type", "access")
                .build();
        String token = sign(claims);

        assertThrows(JwtValidationException.class, () -> buildDecoder().decode(token));
    }

    /**
     * 缺少手机号的令牌必须被拒绝：支付侧无法确定这是谁的钱包，不允许进入业务链路。
     */
    @Test
    void shouldRejectTokenWithoutPhoneNumber() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("1024")
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("uid", 1024)
                .claim("token_type", "access")
                .build();
        String token = sign(claims);

        JwtValidationException error = assertThrows(JwtValidationException.class,
                () -> buildDecoder().decode(token));
        assertTrue(error.getMessage().contains("phone_number"), "错误信息应指出缺少手机号声明");
    }

    /**
     * 手机号写法不规范（无法归一）时同样拒绝，避免把脏号码写成钱包主键。
     */
    @Test
    void shouldRejectTokenWithMalformedPhoneNumber() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("1024")
                .audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("phone_number", "abc")
                .claim("token_type", "access")
                .build();
        String token = sign(claims);

        assertThrows(JwtValidationException.class, () -> buildDecoder().decode(token));
    }

    /**
     * 组装与生产一致的解码器。
     *
     * @return JWT 解码器
     */
    private JwtDecoder buildDecoder() {
        OAuth2ResourceServerProperties serverProperties = new OAuth2ResourceServerProperties();
        serverProperties.getJwt().setIssuerUri(issuer);

        IamResourceServerProperties iamProperties = new IamResourceServerProperties();
        iamProperties.setAudience(AUDIENCE);

        return new PaymentSecurityConfig().iamJwtDecoder(serverProperties, iamProperties);
    }

    /**
     * 构造访问令牌声明。
     *
     * @param tokenType 令牌类型
     * @param audience  受众
     * @return 声明集合
     */
    private JWTClaimsSet claims(String tokenType, String audience) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("1024")
                .audience(audience)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("uid", 1024)
                .claim("phone_number", "15000000000")
                .claim("preferred_username", "zhangsan")
                .claim("token_type", tokenType)
                .claim("token_kind", "APPLICATION")
                .claim("permissions", List.of("payment:order:view"))
                .build();
    }

    /**
     * 用测试私钥签名令牌。
     *
     * @param claims 声明集合
     * @return 紧凑序列化的 JWT
     * @throws Exception 签名失败时抛出
     */
    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }

    /**
     * 写入 JSON 响应。
     *
     * @param exchange HTTP 交换对象
     * @param body     响应体
     */
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 把 BigInteger 转成无符号大端字节数组（JWK 的 n/e 要求）。
     *
     * @param value 数值
     * @return 字节数组
     */
    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /**
     * Base64URL 编码（无填充）。
     *
     * @param bytes 原始字节
     * @return 编码结果
     */
    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
