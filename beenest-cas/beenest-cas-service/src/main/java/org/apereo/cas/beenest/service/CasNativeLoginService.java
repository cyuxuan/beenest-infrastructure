package org.apereo.cas.beenest.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.common.util.CasRequestContextUtils;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apache.commons.lang3.StringUtils;

/**
 * CAS 原生登录执行服务。
 * <p>
 * 统一承接小程序和 refresh 场景中的认证执行、Token 签发和异常映射，
 * 让控制器只保留渠道参数校验与 Credential 构造。
 */
@Slf4j
@RequiredArgsConstructor
public class CasNativeLoginService {

    private final AuthenticationSystemSupport authenticationSystemSupport;
    private final CasTokenLifecycleService tokenLifecycleService;

    /**
     * 执行通用登录并签发 Token。
     *
     * @param credential CAS 凭证
     * @param authType 登录类型标识
     * @param request 当前 HTTP 请求
     * @param deviceId 设备 ID，可为空
     * @return 统一 Token 响应
     */
    public R<TokenResponseDTO> login(Credential credential,
                                     String authType,
                                     HttpServletRequest request,
                                     String deviceId) {
        String clientIp = CasRequestContextUtils.resolveClientIp(request);
        String userAgent = CasRequestContextUtils.resolveUserAgent(request);

        try {
            // 1. 交给 CAS 认证引擎完成凭证校验
            AuthenticationResult authResult = authenticationSystemSupport.finalizeAuthenticationTransaction(credential);
            if (authResult == null || authResult.getAuthentication() == null) {
                return R.fail(401, "认证失败");
            }

            // 2. 认证成功后统一签发 token
            TokenResponseDTO response = tokenLifecycleService.issueToken(
                authResult, authType, clientIp, userAgent, deviceId);
            return R.ok(response);
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("{} 登录失败", authType, e);
            return R.fail(500, "登录失败");
        }
    }

    /**
     * 执行 refreshToken 续期。
     *
     * @param refreshToken 刷新令牌
     * @param request 当前 HTTP 请求
     * @return 统一 Token 响应
     */
    public R<TokenResponseDTO> refresh(String refreshToken, HttpServletRequest request) {
        if (StringUtils.isBlank(refreshToken)) {
            return R.fail(400, "refreshToken 不能为空");
        }

        String clientIp = CasRequestContextUtils.resolveClientIp(request);
        String userAgent = CasRequestContextUtils.resolveUserAgent(request);

        // 1. 先消费 refreshToken，确保旧令牌只能用一次
        String userId = tokenLifecycleService.consumeRefreshToken(refreshToken);
        if (userId == null) {
            return R.fail(401, "refreshToken 已过期或已被使用");
        }

        try {
            // 2. 直接按 userId 续签，不再经过任何桥接凭证
            TokenResponseDTO response = tokenLifecycleService.issueTokenForUserId(
                userId, "TOKEN_REFRESH", clientIp, userAgent, null);
            return R.ok(response);
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("Token 刷新失败: userId={}", userId, e);
            return R.fail(500, "Token 刷新失败");
        }
    }
}
