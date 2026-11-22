package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ApplicationServiceDtoConverter;
import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用服务令牌受众校验测试。
 * <p>受众写错是**静默故障**:令牌照常签发,只是每次调用都被资源服务器按"受众不匹配"拒掉,
 * 现象离"配置写错了"很远。三条负向用例锁定最常见的三种错法,正向用例确认多受众本身合法。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceServiceImplTest {

    /** 当前域主键 */
    private static final Long REALM_ID = 1L;
    /** 应用编码 */
    private static final String APP_CODE = "oa";

    @Mock
    private IamApplicationServiceManager appServiceManager;
    @Mock
    private IamAppAdminManager appAdminManager;
    @Mock
    private IamAppRoleMappingManager appRoleMappingManager;
    @Mock
    private IamClientManager clientManager;
    @Mock
    private IamRoleManager roleManager;
    @Mock
    private IamUserManager userManager;
    @Mock
    private IamGroupManager groupManager;
    @Mock
    private IamPermissionManager permissionManager;
    @Mock
    private ApplicationAccessRevocationService revocationService;

    private ApplicationServiceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApplicationServiceServiceImpl(appServiceManager, appAdminManager, appRoleMappingManager,
                clientManager, roleManager, userManager, groupManager, permissionManager,
                new ApplicationServiceDtoConverter(), revocationService);
        IamUserPrincipal principal = new IamUserPrincipal(7L, REALM_ID, "default", "admin", "管理员",
                0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("用中文逗号把两个受众连写:拒绝并指出是标点问题")
    void rejectsChineseCommaJoinedAudience() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(request(List.of("urn:iam:app:drone，urn:iam:app:payment"))));

        assertTrue(error.getMessage().contains("中文标点"), error.getMessage());
        verify(appServiceManager, never()).create(any());
    }

    @Test
    @DisplayName("受众里夹带空格:拒绝")
    void rejectsAudienceWithWhitespace() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(request(List.of("urn:iam:app:drone payment"))));

        assertTrue(error.getMessage().contains("空格"), error.getMessage());
    }

    @Test
    @DisplayName("受众超过 5 个:拒绝")
    void rejectsTooManyAudiences() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(request(List.of("a1", "a2", "a3", "a4", "a5", "a6"))));

        assertTrue(error.getMessage().contains("最多"), error.getMessage());
    }

    @Test
    @DisplayName("两个合法受众:放行并落库")
    void acceptsTwoValidAudiences() {
        when(appServiceManager.getByAppCode(REALM_ID, APP_CODE)).thenReturn(null);
        AtomicReference<ApplicationService> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            ApplicationService app = invocation.getArgument(0);
            app.setId(9L);
            saved.set(app);
            return null;
        }).when(appServiceManager).create(any());
        when(appServiceManager.getById(any())).thenAnswer(invocation -> saved.get());

        assertNotNull(service.create(request(List.of("urn:iam:app:drone", "urn:iam:app:payment"))));
        verify(appServiceManager).create(any());
    }

    /**
     * 构造创建请求。
     *
     * @param audiences 令牌受众
     * @return 创建请求
     */
    private static ApplicationServiceCreateRequest request(List<String> audiences) {
        // 接入方式取 SAML、可见性取 PUBLIC:把用例钉在受众校验上,
        // 不被"OIDC 必须绑客户端""按权限可见必须给权限码"这两条无关规则拦住
        return new ApplicationServiceCreateRequest(
                APP_CODE, audiences, "协同办公", "WEB", "SAML", null,
                null, null, null, null, null, null, null,
                "PUBLIC", null, null);
    }
}
