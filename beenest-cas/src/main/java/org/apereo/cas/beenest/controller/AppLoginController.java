package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.authn.credential.AppTokenCredential;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.common.util.CasAttributeUtils;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.AppLoginRequestDTO;
import org.apereo.cas.beenest.dto.AppLogoutRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.beenest.service.AppAccessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketFactory;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * APP 登录 REST 控制器
 * <p>
 * APP 登录返回 JSON token（不触发浏览器重定向）。
 * 支持密码、短信验证码登录。
 * <p>
 * refreshToken 续期由统一的 {@code /refresh} 端点处理。
 * <p>
 * Token 有效期通过 {@link TokenTtlProperties} 配置：
 * - accessToken (TGT) 默认 7 天
 * - refreshToken 默认 365 天（1 年），可通过环境变量覆盖
 * - refreshToken 支持轮换机制，每次刷新生成新 token 并原子删除旧 token
 */
@Slf4j
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
public class AppLoginController {

    private final AuthenticationSystemSupport authenticationSystemSupport;
    private final TicketRegistry ticketRegistry;
    private final DefaultTicketFactory defaultTicketFactory;
    private final StringRedisTemplate redisTemplate;
    private final AuthAuditService auditService;
    private final AppAccessService appAccessService;
    private final UnifiedUserMapper userMapper;
    private final TokenTtlProperties tokenTtlProperties;

    /**
     * APP 登录
     *
     * @param request { principal, password, otpCode, loginMethod, deviceId, rememberMe }
     * @return { accessToken, refreshToken, expiresIn, userId, attributes }
     */
    @PostMapping("/login")
    public R<TokenResponseDTO> login(@RequestBody AppLoginRequestDTO request,
                                        HttpServletRequest httpRequest) {
        String loginPrincipal = request.getPrincipal() != null ? request.getPrincipal() : "";
        String loginMethod = StringUtils.lowerCase(StringUtils.defaultIfBlank(request.getLoginMethod(), "password"));
        String deviceId = request.getDeviceId();
        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (!"password".equalsIgnoreCase(loginMethod)
                && !"refresh".equalsIgnoreCase(loginMethod)
                && !"sms".equalsIgnoreCase(loginMethod)) {
            return R.fail(400, "不支持的登录方式");
        }

        if ("refresh".equalsIgnoreCase(loginMethod)) {
            return R.fail(400, "请使用 /cas/refresh 端点进行 Token 续期");
        }

        if ("sms".equalsIgnoreCase(loginMethod)) {
            return R.fail(400, "请使用短信认证入口进行短信验证码登录");
        }

        if (StringUtils.isBlank(request.getPrincipal()) || StringUtils.isBlank(request.getPassword())) {
            return R.fail(400, "用户名密码登录需要用户名和密码");
        }

        AppTokenCredential credential = new AppTokenCredential();
        credential.setPrincipal(request.getPrincipal());
        credential.setPassword(request.getPassword());
        credential.setOtpCode(request.getOtpCode());
        credential.setRefreshToken(request.getRefreshToken());
        credential.setDeviceId(deviceId);
        credential.setLoginMethod(loginMethod);
        credential.setRememberMe(Boolean.TRUE.equals(request.getRememberMe()));

        String authType = "APP_" + loginMethod.toUpperCase();

        try {
            // 1. 执行认证
            AuthenticationResult authResult = authenticationSystemSupport.finalizeAuthenticationTransaction(credential);

            if (authResult == null || authResult.getAuthentication() == null) {
                auditService.record(null, loginPrincipal, authType, "FAILED", "认证结果为空",
                    clientIp, userAgent, deviceId, null, "appTokenAuthenticationHandler");
                return R.fail(401, "认证失败");
            }

            Principal principal = authResult.getAuthentication().getPrincipal();

            Long serviceId = resolveBusinessServiceId(httpRequest);
            if (serviceId != null) {
                // 全新系统不考虑历史用户，登录成功后直接补齐当前业务系统授权。
                appAccessService.autoGrantOnRegister(principal.getId(), serviceId);
            }

            // 2. 创建 TGT
            TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory =
                    (TicketGrantingTicketFactory<TicketGrantingTicket>) defaultTicketFactory.get(TicketGrantingTicket.class);
            TicketGrantingTicket tgt = tgtFactory.create(authResult.getAuthentication(), null);
            ticketRegistry.addTicket(tgt);
            LOGGER.info("APP 登录 TGT 签发成功: userId={}, authType={}, tgtId={}...",
                    principal.getId(), authType, tgt.getId().substring(0, Math.min(tgt.getId().length(), 16)));

            // 3. 更新用户最近登录信息
            try {
                userMapper.updateLoginInfo(principal.getId(), clientIp, userAgent, deviceId, authType);
            } catch (Exception e) {
                LOGGER.warn("更新用户登录信息失败: userId={}", principal.getId(), e);
            }

            // 4. 生成 refreshToken（统一使用单一前缀）
            String refreshToken = UUID.randomUUID().toString().replace("-", "");
            long refreshTtl = tokenTtlProperties.getRefreshTokenTtlSeconds();
            String refreshKey = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken;
            redisTemplate.opsForValue().set(refreshKey, principal.getId(), refreshTtl, TimeUnit.SECONDS);

            // 5. 记录成功审计日志
            auditService.record(principal.getId(), loginPrincipal, authType, "SUCCESS", null,
                clientIp, userAgent, deviceId, String.valueOf(serviceId), "appTokenAuthenticationHandler");

            // 6. 构建返回数据
            return R.ok(buildTokenResponse(tgt.getId(), refreshToken, principal));

        } catch (BusinessException e) {
            auditService.record(null, loginPrincipal, authType, "FAILED", e.getMessage(),
                clientIp, userAgent, deviceId, null, "appTokenAuthenticationHandler");
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("APP 登录失败", e);
            auditService.record(null, loginPrincipal, authType, "FAILED", e.getMessage(),
                clientIp, userAgent, deviceId, null, "appTokenAuthenticationHandler");
            return R.fail(500, "登录失败");
        }
    }

    /**
     * APP 登出
     * <p>
     * 清除 TGT、refreshToken，防止已注销的 token 被复用。
     *
     * @param request { refreshToken, accessToken }
     * @return 登出结果
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody AppLogoutRequestDTO request,
                          HttpServletRequest httpRequest) {
        String refreshToken = request.getRefreshToken();
        String accessToken = request.getAccessToken();
        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 1. 清除 refreshToken
        if (StringUtils.isNotBlank(refreshToken)) {
            redisTemplate.delete(CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken);
            redisTemplate.delete(CasConstant.REDIS_APP_TOKEN_PREFIX + "refresh:" + refreshToken);
            redisTemplate.delete(CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + refreshToken);
        }

        // 2. 销毁 TGT
        String userId = null;
        if (StringUtils.isNotBlank(accessToken)) {
            try {
                TicketGrantingTicket tgt = ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class);
                if (tgt != null) {
                    userId = tgt.getAuthentication().getPrincipal().getId();
                    ticketRegistry.deleteTicket(accessToken);
                    LOGGER.info("APP 登出成功，TGT 已销毁: {}", accessToken);
                }
            } catch (Exception e) {
                // TGT 可能已过期，忽略
                LOGGER.debug("APP 登出时 TGT 不存在或已过期: {}", accessToken);
            }
        }

        // 3. 记录登出审计日志
        auditService.record(userId, userId, "APP_LOGOUT", "SUCCESS", null,
            clientIp, userAgent, null, null, "appTokenAuthenticationHandler");

        return R.ok(null);
    }

    /**
     * 获取客户端真实 IP（支持代理转发场景）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private TokenResponseDTO buildTokenResponse(String accessToken, String refreshToken, Principal principal) {
        TokenResponseDTO data = new TokenResponseDTO();
        data.setAccessToken(accessToken);
        data.setTgt(accessToken);
        data.setRefreshToken(refreshToken);
        data.setExpiresIn(tokenTtlProperties.getAccessTokenTtlSeconds());
        data.setUserId(principal.getId());
        data.setAttributes(CasAttributeUtils.flattenAttributes(principal.getAttributes()));
        return data;
    }

    private Long resolveBusinessServiceId(HttpServletRequest request) {
        String header = request.getHeader(CasConstant.BUSINESS_SERVICE_ID_HEADER);
        if (StringUtils.isBlank(header)) {
            header = request.getHeader(CasConstant.SERVICE_ID_HEADER);
        }
        if (StringUtils.isBlank(header)) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
