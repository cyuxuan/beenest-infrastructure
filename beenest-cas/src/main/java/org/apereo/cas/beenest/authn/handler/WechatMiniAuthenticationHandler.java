package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.WechatMiniCredential;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.service.UserIdentityService;
import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
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

import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信小程序认证处理器
 * <p>
 * 通过 wx.login 的 code 调用 code2session 获取 openid，
 * 查找或自动注册用户，支持通过手机号合并已有账号。
 */
@Slf4j
@RequiredArgsConstructor
public class WechatMiniAuthenticationHandler implements AuthenticationHandler {

    private final String name;
    private final PrincipalFactory principalFactory;
    private final WxMaService wxMaService;
    private final UserIdentityService userIdentityService;

    @Override
    public AuthenticationHandlerExecutionResult authenticate(Credential credential, Service service) throws Throwable {
        WechatMiniCredential wechatCredential = (WechatMiniCredential) credential;
        String code = wechatCredential.getCode();

        if (StringUtils.isBlank(code)) {
            throw new FailedLoginException("微信授权码不能为空");
        }

        // 1. code2session 获取 openid 和 session_key
        WxMaJscode2SessionResult session;
        try {
            session = wxMaService.getUserService().getSessionInfo(code);
        } catch (Exception e) {
            LOGGER.error("微信 code2session 失败", e);
            throw new FailedLoginException("微信登录失败");
        }

        String openid = session.getOpenid();
        String unionid = session.getUnionid();
        LOGGER.info("微信登录: openid={}, unionid={}", openid, unionid);

        // 2. 尝试获取手机号
        String phone = null;
        if (StringUtils.isNotBlank(wechatCredential.getPhoneCode())) {
            phone = getPhoneFromWechat(wechatCredential.getPhoneCode());
        }

        // 3. 查找或注册用户
        UserIdentityService.UserIdentityResult identityResult = userIdentityService.findOrRegisterByWechatResult(
                openid, unionid, phone,
                wechatCredential.getUserType(),
                wechatCredential.getNickname());

        UnifiedUserDO user = identityResult.user();

        if (user == null) {
            throw new FailedLoginException("微信登录失败：无法获取用户信息");
        }

        // 4. 构建 Principal 和属性
        Map<String, List<Object>> attributes = buildUserAttributes(user, identityResult.firstLogin());
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);

        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    @Override
    public boolean supports(Credential credential) {
        return credential instanceof WechatMiniCredential;
    }

    @Override
    public boolean supports(Class<? extends Credential> clazz) {
        return WechatMiniCredential.class.isAssignableFrom(clazz);
    }

    @Override
    public String getName() {
        return this.name;
    }

    /**
     * 通过微信 phoneCode 获取手机号
     */
    private String getPhoneFromWechat(String phoneCode) {
        try {
            WxMaPhoneNumberInfo phoneInfo = wxMaService.getUserService().getPhoneNoInfo(phoneCode);
            return phoneInfo.getPhoneNumber();
        } catch (Exception e) {
            LOGGER.warn("获取微信手机号失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建用户属性 Map，用于 CAS attribute release
     */
    private Map<String, List<Object>> buildUserAttributes(UnifiedUserDO user, boolean firstLogin) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("userId", List.of(user.getUserId()));
        attrs.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attrs.put("loginType", List.of("WECHAT"));
        attrs.put("firstLogin", List.of(firstLogin));
        if (StringUtils.isNotBlank(user.getUsername())) {
            attrs.put("username", List.of(user.getUsername()));
        }
        if (StringUtils.isNotBlank(user.getNickname())) {
            attrs.put("nickname", List.of(user.getNickname()));
        }
        if (StringUtils.isNotBlank(user.getPhone())) {
            attrs.put("phone", List.of(user.getPhone()));
        }
        if (StringUtils.isNotBlank(user.getEmail())) {
            attrs.put("email", List.of(user.getEmail()));
        }
        return attrs;
    }
}
