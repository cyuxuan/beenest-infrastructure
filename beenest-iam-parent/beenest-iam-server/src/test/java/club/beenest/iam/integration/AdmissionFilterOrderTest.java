package club.beenest.iam.integration;

import club.beenest.iam.authz.admission.ClientAdmissionFilter;
import club.beenest.iam.authz.tenant.RealmContextFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准入过滤器的链上次序。
 * <p>排错的失败是静默的:过滤器注册成功、应用正常启动,只是永远不执行。
 * 只有把次序钉进测试,才有人能发现"准入闸门其实没装"。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdmissionFilterOrderTest extends IamIntegrationTestBase {

    // 容器与属性的声明照抄 IamWechatLoginIntegrationTest 的 @Container / @DynamicPropertySource
    // (基类不能代劳:Testcontainers 的扩展在声明它的类结束时停容器)
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("iam.realm-key.master-secret", () -> "integration-test-master-secret");
        registry.add("iam.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("iam.bootstrap.enabled", () -> "true");
    }

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    @DisplayName("准入过滤器排在域上下文之后、授权端点之前")
    void admissionFilterRunsBetweenRealmContextAndAuthorizeEndpoint() {
        List<String> filters = filterChainProxy.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .map(filter -> filter.getClass().getName())
                .toList();

        int realmContext = indexOf(filters, RealmContextFilter.class);
        int admission = indexOf(filters, ClientAdmissionFilter.class);
        int authorizeEndpoint = indexOf(filters, OAuth2AuthorizationEndpointFilter.class);

        assertTrue(realmContext >= 0, "找不到域上下文过滤器");
        assertTrue(admission >= 0, "找不到准入过滤器:它根本没被注册进任何链");
        assertTrue(authorizeEndpoint >= 0, "找不到授权端点过滤器");
        assertTrue(realmContext < admission, "准入过滤器必须晚于域上下文(否则拿不到域,查不到客户端)");
        assertTrue(admission < authorizeEndpoint, "准入过滤器必须早于授权端点(否则拦不住请求)");
    }

    private static int indexOf(List<String> names, Class<?> type) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(type.getName())) {
                return i;
            }
        }
        return -1;
    }
}
