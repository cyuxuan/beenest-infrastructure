package club.beenest.iam.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 分组配置单测:固化"四分组路径 + 认证组不挂 bearer"的约定,
 * 防止后续加分组时漏配安全方案或改错路径。
 */
class OpenApiConfigurationTest {

    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    @Test
    @DisplayName("四个分组按调用方切分,路径与显示名齐备")
    void groupsPartitionApiSurface() {
        assertGroup(configuration.adminApi(), "admin", "管理端 API", "/api/v1/admin/**");
        assertGroup(configuration.consoleApi(), "console", "用户自助端 API", "/api/v1/console/**");
        assertGroup(configuration.launchpadApi(), "launchpad", "应用门户 API", "/api/v1/launchpad/**");
        assertGroup(configuration.authApi(), "auth", "认证接口", "/api/v1/auth/**");
    }

    @Test
    @DisplayName("全局文档注册 bearerAuth 安全方案")
    void globalApiRegistersBearerScheme() {
        OpenAPI openApi = configuration.beenestOpenApi();
        assertNotNull(openApi.getInfo());
        assertEquals("蜂巢身份 API (beenest IAM)", openApi.getInfo().getTitle());
        assertNotNull(openApi.getComponents().getSecuritySchemes().get("bearerAuth"));
        assertEquals("bearer",
                openApi.getComponents().getSecuritySchemes().get("bearerAuth").getScheme());
    }

    @Test
    @DisplayName("业务分组自动补 bearer 要求;认证组显式摘除")
    void bearerAppliedToBusinessGroupsOnly() {
        assertEquals(1, configuration.adminApi().getOpenApiCustomizers().size());
        assertEquals(1, configuration.authApi().getOpenApiCustomizers().size());

        // 业务分组:给无 security 的操作补上 bearerAuth
        Operation adminOp = applyFirstCustomizer(configuration.adminApi());
        assertFalse(adminOp.getSecurity().isEmpty());
        assertEquals("bearerAuth", adminOp.getSecurity().get(0).keySet().iterator().next());

        // 认证分组:显式清空 security(登录链路本身无需认证)
        Operation authOp = applyFirstCustomizer(configuration.authApi());
        assertTrue(authOp.getSecurity() == null || authOp.getSecurity().isEmpty());
    }

    private void assertGroup(GroupedOpenApi group, String name, String displayName, String path) {
        assertEquals(name, group.getGroup());
        assertEquals(displayName, group.getDisplayName());
        assertTrue(group.getPathsToMatch().contains(path), name + " 分组缺少路径 " + path);
    }

    /** 取出分组的唯一 customizer,作用于一个带 GET 操作的文档,返回该操作。 */
    private Operation applyFirstCustomizer(GroupedOpenApi group) {
        OpenApiCustomizer customizer = group.getOpenApiCustomizers().iterator().next();
        Paths paths = new Paths();
        paths.addPathItem("/api/v1/admin/users", new PathItem().get(new Operation()));
        OpenAPI openApi = new OpenAPI().paths(paths);
        customizer.customise(openApi);
        return openApi.getPaths().get("/api/v1/admin/users").getGet();
    }
}
