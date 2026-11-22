package club.beenest.iam.authz.principal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 主体序列化测试。
 * <p>主体快照存 Redis,升级前签发的主体没有 loginType 字段。
 * 反序列化时该字段必须为 null 而不是抛异常——升级当天所有在线会话
 * 都读不出来,等于把全站用户踢下线。</p>
 */
class IamUserPrincipalTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("存量主体快照缺 loginType 时反序列化为 null")
    void readsLegacyPrincipalWithoutLoginType() throws Exception {
        String legacy = "{\"userId\":1,\"realmId\":1,\"realmKey\":\"default\","
                + "\"username\":\"zhang\",\"nickname\":\"张三\",\"tokenVersion\":0,"
                + "\"authorities\":[],\"attributes\":{},\"permissionEpoch\":3}";

        IamUserPrincipal principal = mapper.readValue(legacy, IamUserPrincipal.class);

        assertNull(principal.getLoginType());
        assertEquals("zhang", principal.getUsername());
    }

    @Test
    @DisplayName("loginType 往返不丢")
    void roundTripsLoginType() throws Exception {
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "LDAP");

        IamUserPrincipal read = mapper.readValue(mapper.writeValueAsString(principal), IamUserPrincipal.class);

        assertEquals("LDAP", read.getLoginType());
    }
}
