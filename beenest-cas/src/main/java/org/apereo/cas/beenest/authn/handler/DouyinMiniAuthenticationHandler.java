package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.DouyinMiniCredential;
import org.apereo.cas.beenest.config.MiniAppProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.service.UserIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.DefaultAuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.Service;
import org.springframework.web.client.RestTemplate;

import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 抖音小程序认证处理器
 * <p>
 * 通过 tt.login 的 code 调用抖音 code2session 获取 openid，
 * 查找或自动注册用户。
 */
@Slf4j
@RequiredArgsConstructor
public class DouyinMiniAuthenticationHandler implements AuthenticationHandler {

    private final String name;
    private final PrincipalFactory principalFactory;
    private final MiniAppProperties miniAppProperties;
    private final UserIdentityService userIdentityService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AuthenticationHandlerExecutionResult authenticate(Credential credential, Service service) throws Throwable {
        DouyinMiniCredential douyinCredential = (DouyinMiniCredential) credential;
        String code = douyinCredential.getDouyinCode();

        if (StringUtils.isBlank(code)) {
            throw new FailedLoginException("抖音授权码不能为空");
        }

        MiniAppProperties.DouyinConfig config = miniAppProperties.getDouyin();
        if (config == null || StringUtils.isBlank(config.getAppid())) {
            throw new FailedLoginException("抖音小程序未配置");
        }

        // 1. 调用抖音 code2session API
        String url = String.format(
                "https://developer.toutiao.com/api/apps/v2/jscode2session?appid=%s&secret=%s&code=%s",
                config.getAppid(), config.getSecret(), code);

        String openid;
        String unionid;
        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode json = objectMapper.readTree(response);

            int errorCode = json.path("err_no").asInt(-1);
            if (errorCode != 0) {
                LOGGER.error("抖音 code2session 失败: {}", json.path("err_tips").asText());
                throw new FailedLoginException("抖音登录失败");
            }

            openid = json.path("data").path("openid").asText();
            unionid = json.path("data").path("unionid").asText();
            LOGGER.info("抖音登录: openid={}", openid);
        } catch (FailedLoginException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("抖音登录异常", e);
            throw new FailedLoginException("抖音登录失败");
        }

        // 2. 查找或注册用户
        UserIdentityService.UserIdentityResult identityResult = userIdentityService.findOrRegisterByDouyinResult(
                openid, unionid, null,
                douyinCredential.getUserType(),
                douyinCredential.getNickname());

        UnifiedUserDO user = identityResult.user();

        if (user == null) {
            throw new FailedLoginException("抖音登录失败：无法获取用户信息");
        }

        // 3. 构建 Principal
        Map<String, List<Object>> attributes = buildUserAttributes(user, identityResult.firstLogin());
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);

        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    @Override
    public boolean supports(Credential credential) {
        return credential instanceof DouyinMiniCredential;
    }

    @Override
    public boolean supports(Class<? extends Credential> clazz) {
        return DouyinMiniCredential.class.isAssignableFrom(clazz);
    }

    @Override
    public String getName() {
        return this.name;
    }

    private Map<String, List<Object>> buildUserAttributes(UnifiedUserDO user, boolean firstLogin) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("userId", List.of(user.getUserId()));
        attrs.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attrs.put("loginType", List.of("DOUYIN_MINI"));
        attrs.put("firstLogin", List.of(firstLogin));
        if (StringUtils.isNotBlank(user.getNickname())) {
            attrs.put("nickname", List.of(user.getNickname()));
        }
        if (StringUtils.isNotBlank(user.getPhone())) {
            attrs.put("phone", List.of(user.getPhone()));
        }
        return attrs;
    }
}
