package club.beenest.iam.authz.web;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamUserSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 会话安全上下文仓库测试。
 * <p>保存上下文不补建会话:表单登录的认证过滤器在成功处理器之前先保存一次,
 * 此处补建会让一次登录落两条会话,其中一条没有登录事件且 Cookie 被覆盖,
 * 在在线会话列表里长期显示为在线。</p>
 */
class IamRedisSecurityContextRepositoryTest {

    private final IamUserSessionService userSessionService = mock(IamUserSessionService.class);
    private final IamRedisSecurityContextRepository repository =
            new IamRedisSecurityContextRepository(userSessionService);

    @Test
    @DisplayName("无会话标识时保存上下文不建会话、不下发 Cookie")
    void doesNotCreateSessionWhenNoSessionId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveContext(authenticated(), request, response);

        verifyNoInteractions(userSessionService);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(request.getAttribute(IamRedisSecurityContextRepository.SESSION_ID_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("未认证的上下文不触发任何动作")
    void ignoresAnonymousContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);

        verifyNoInteractions(userSessionService);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    /**
     * 已认证的上下文。
     *
     * @return 带用户主体的安全上下文
     */
    private static SecurityContext authenticated() {
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        return context;
    }
}
