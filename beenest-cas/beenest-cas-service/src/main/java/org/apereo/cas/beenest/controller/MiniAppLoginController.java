package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.authn.credential.AlipayMiniCredential;
import org.apereo.cas.beenest.authn.credential.DouyinMiniCredential;
import org.apereo.cas.beenest.authn.credential.WechatMiniCredential;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.MiniAppLoginDTO;
import org.apereo.cas.beenest.dto.MiniAppLogoutDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.CasNativeLoginService;
import org.apereo.cas.beenest.service.CasTokenLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.Credential;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序登录 REST 控制器。
 * <p>
 * 微信、抖音、支付宝小程序都在这里映射成 CAS 原生 Credential，
 * 然后交给 CAS 认证引擎处理。控制器只保留渠道差异，Token 生命周期统一下沉到服务层。
 */
@Slf4j
@RestController
@RequestMapping("/miniapp")
@RequiredArgsConstructor
public class MiniAppLoginController {

    private final CasNativeLoginService nativeLoginService;
    private final CasTokenLifecycleService tokenLifecycleService;

    /**
     * 微信小程序登录。
     *
     * @param dto 登录参数
     * @param httpRequest 当前 HTTP 请求
     * @return Token 响应
     */
    @PostMapping("/wechat/login")
    public R<TokenResponseDTO> wechatLogin(@RequestBody MiniAppLoginDTO dto,
                                           HttpServletRequest httpRequest) {
        if (StringUtils.isBlank(dto.getCode())) {
            return R.fail(400, "微信授权码不能为空");
        }

        WechatMiniCredential credential = new WechatMiniCredential(dto.getCode());
        credential.setPhoneCode(dto.getPhoneCode());
        credential.setUserType(dto.getUserType());
        credential.setNickname(dto.getNickname());

        return executeLogin(credential, "WECHAT", httpRequest);
    }

    /**
     * 抖音小程序登录。
     *
     * @param dto 登录参数
     * @param httpRequest 当前 HTTP 请求
     * @return Token 响应
     */
    @PostMapping("/douyin/login")
    public R<TokenResponseDTO> douyinLogin(@RequestBody MiniAppLoginDTO dto,
                                           HttpServletRequest httpRequest) {
        if (StringUtils.isBlank(dto.getDouyinCode())) {
            return R.fail(400, "抖音授权码不能为空");
        }

        DouyinMiniCredential credential = new DouyinMiniCredential(dto.getDouyinCode());
        credential.setUserType(dto.getUserType());
        credential.setNickname(dto.getNickname());

        return executeLogin(credential, "DOUYIN_MINI", httpRequest);
    }

    /**
     * 支付宝小程序登录。
     *
     * @param dto 登录参数
     * @param httpRequest 当前 HTTP 请求
     * @return Token 响应
     */
    @PostMapping("/alipay/login")
    public R<TokenResponseDTO> alipayLogin(@RequestBody MiniAppLoginDTO dto,
                                           HttpServletRequest httpRequest) {
        if (StringUtils.isBlank(dto.getAuthCode()) && StringUtils.isBlank(dto.getPhoneCode())) {
            return R.fail(400, "支付宝授权码或手机号授权码不能同时为空");
        }

        AlipayMiniCredential credential = new AlipayMiniCredential(dto.getAuthCode());
        credential.setPhoneCode(dto.getPhoneCode());
        credential.setUserType(dto.getUserType());
        credential.setNickname(dto.getNickname());

        return executeLogin(credential, "ALIPAY_MINI", httpRequest);
    }

    /**
     * 小程序登出。
     *
     * @param dto 登出参数
     * @return 空响应
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody MiniAppLogoutDTO dto) {
        tokenLifecycleService.revokeTokens(dto.getAccessToken(), dto.getRefreshToken());
        return R.ok(null);
    }

    /**
     * 执行认证并签发 Token。
     *
     * @param credential CAS 凭证
     * @param authType 登录类型标识
     * @param httpRequest 当前 HTTP 请求
     * @return Token 响应
     */
    private R<TokenResponseDTO> executeLogin(Credential credential,
                                             String authType,
                                             HttpServletRequest httpRequest) {
        return nativeLoginService.login(credential, authType, httpRequest, null);
    }
}
