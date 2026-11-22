package club.beenest.iam.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。
 * <p>按调用方分为 admin、console、launchpad、auth 四组;
 * OAuth2/OIDC 协议端点由 Spring Authorization Server 提供,不在文档范围内。
 * 整组文档可通过 {@code springdoc.api-docs.enabled=false} 关闭。</p>
 */
@Configuration
public class OpenApiConfiguration {

    /** 认证方式:除登录/验证码等公开接口外,一律 Bearer JWT(或浏览器会话)。 */
    private static final String SECURITY_SCHEME = "bearerAuth";

    /**
     * 文档元信息与 bearerAuth 安全方案。
     *
     * @return OpenAPI 定义
     */
    @Bean
    public OpenAPI beenestOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("蜂巢身份 API (beenest IAM)")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                统一身份认证与 IAM 管理接口。

                                **统一响应包装**:所有 REST 接口返回 `Result<T>`:
                                `code=0` 为成功,业务语义由 `code` 表达,HTTP 状态码表达传输层事实;
                                `message` 为默认文案,详情在 `data`。

                                **认证**:除「认证」组与少数公开端点外,接口要求已认证——
                                浏览器场景走会话 Cookie,服务间调用走 `Authorization: Bearer <token>`
                                (client_credentials 或授权码流程签发)。

                                **多租户**:管理端与自助端接口的数据范围由登录账号所属身份域决定;
                                协议端点按 `/realms/{realmKey}/...` 路径定位租户。
                                """)
                        .contact(new Contact().name("beenest IAM").url("https://iam.beenest.club"))
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Bearer JWT(access token);浏览器会话同样可用")));
    }

    /**
     * 管理端 API 分组。
     *
     * @return 分组定义
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return group("admin", "管理端 API", "/api/v1/admin/**");
    }

    /**
     * 用户自助端 API 分组。
     *
     * @return 分组定义
     */
    @Bean
    public GroupedOpenApi consoleApi() {
        return group("console", "用户自助端 API", "/api/v1/console/**");
    }

    /**
     * 应用门户 API 分组。
     *
     * @return 分组定义
     */
    @Bean
    public GroupedOpenApi launchpadApi() {
        return group("launchpad", "应用门户 API", "/api/v1/launchpad/**");
    }

    /**
     * 认证接口分组。
     * <p>该组端点是登录链路入口,不声明认证要求。</p>
     *
     * @return 分组定义
     */
    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth")
                .displayName("认证接口")
                .pathsToMatch("/api/v1/auth/**")
                // 认证接口是登录链路的入口,本身无需认证;不挂 bearer 方案说明
                .addOpenApiCustomizer(openApi -> openApi.getPaths().values()
                        .forEach(pathItem -> pathItem.readOperations()
                                .forEach(operation -> operation.security(null))))
                .build();
    }

    /**
     * 构建带 bearerAuth 安全要求的 API 分组。
     *
     * @param name        分组标识
     * @param displayName 分组展示名
     * @param paths       路径匹配表达式
     * @return 分组定义
     */
    private GroupedOpenApi group(String name, String displayName, String paths) {
        return GroupedOpenApi.builder()
                .group(name)
                .displayName(displayName)
                .pathsToMatch(paths)
                .addOpenApiCustomizer(bearerCustomizer())
                .build();
    }

    /**
     * 为未声明安全要求的操作补上 bearerAuth。
     *
     * @return OpenAPI 定制器
     */
    private OpenApiCustomizer bearerCustomizer() {
        return openApi -> openApi.getPaths().values()
                .forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                    if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
                        operation.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
                    }
                }));
    }
}
