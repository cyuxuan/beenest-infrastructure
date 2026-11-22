package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话快照序列化测试。
 * <p>会话快照存 Redis,升级前签发的快照没有 mfaVerified 字段。
 * 用原始类型 boolean 会让 Jackson 在反序列化时直接抛异常——
 * 升级当天所有在线会话读不出来,全站掉登录。</p>
 */
class IamSessionPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("存量会话快照缺 mfaVerified 时反序列化为 null,语义为未完成")
    void readsLegacyPayload() throws Exception {
        String legacy = "{\"principal\":null,\"createdTime\":1000,\"maxLifetimeSeconds\":3600,"
                + "\"idleSeconds\":1800,\"lastTouchMillis\":2000}";

        IamSessionPayload payload = mapper.readValue(legacy, IamSessionPayload.class);

        assertNull(payload.mfaVerified());
        assertFalse(Boolean.TRUE.equals(payload.mfaVerified()));
    }

    @Test
    @DisplayName("升级只改认证字段,存续起算点与时长原样保留")
    void upgradeKeepsLifetime() {
        // principal 必须非空:withAuthentication 对 null 主体是"原样保留",
        // 传 null 会在断言 principal().getLoginType() 时 NPE,测不到想测的东西
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(principal, 1000L, 3600L, 1800L, 2000L, null);

        IamSessionPayload upgraded = payload.withAuthentication("SMS", Boolean.TRUE);

        assertEquals(1000L, upgraded.createdTime());
        assertEquals(3600L, upgraded.maxLifetimeSeconds());
        assertEquals(1800L, upgraded.idleSeconds());
        assertEquals(2000L, upgraded.lastTouchMillis());
        assertEquals("SMS", upgraded.principal().getLoginType());
        assertTrue(upgraded.mfaVerified());
    }

    @Test
    @DisplayName("只补二步验证时不清掉已有的第一因素方式")
    void nullLoginTypeKeepsExistingMethod() {
        // 客户端级 requireMfa 的补验只补第二因素,loginType 传 null;
        // 若这时把主体重建成"方式未知",该会话下一次准入判定又会被要求补验第一因素
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(principal, 1000L, 3600L, 1800L, 2000L, null);

        IamSessionPayload upgraded = payload.withAuthentication(null, Boolean.TRUE);

        assertEquals("PASSWORD", upgraded.principal().getLoginType());
        assertTrue(upgraded.mfaVerified());
    }
}
