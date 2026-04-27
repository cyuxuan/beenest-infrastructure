package org.apereo.cas.beenest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.credential.UsernamePasswordCredential;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.beenest.authn.credential.SmsOtpCredential;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.common.util.CasAttributeUtils;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.AppLoginRequestDTO;
import org.apereo.cas.beenest.dto.AppLogoutRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketFactory;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * APP 登录控制器。
 * <p>
 * 对外仅暴露业务系统域名下的 `/app/login` 与 `/app/logout`，
 * 具体认证交由 CAS 原生认证处理器完成。
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
    private final AppAccessService appAccessService;
    private final UnifiedUserMapper userMapper;
    private final TokenTtlProperties tokenTtlProperties;

    /**
     * APP 登录。
     *
     * @param request 登录请求
     * @param httpRequest 当前 HTTP 请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public R<TokenResponseDTO> login(@RequestBody AppLoginRequestDTO request, HttpServletRequest httpRequest) {
        String loginMethod = StringUtils.lowerCase(StringUtils.defaultIfBlank(request.getLoginMethod(), "password"));
        String loginPrincipal = StringUtils.defaultString(request.getPrincipal()).trim();
        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String deviceId = StringUtils.defaultString(request.getDeviceId());

        // 1. 参数校验
        if (!"password".equals(loginMethod) && !"sms".equals(loginMethod)) {
            return R.fail(400, "不支持的登录方式");
        }
        if (StringUtils.isBlank(loginPrincipal)) {
            return R.fail(400, "登录账号不能为空");
        }

        Credential credential;
        String authType;
        if ("sms".equals(loginMethod)) {
            if (StringUtils.isBlank(request.getOtpCode())) {
                return R.fail(400, "短信验证码不能为空");
            }
            SmsOtpCredential smsCredential = new SmsOtpCredential(loginPrincipal, request.getOtpCode());
            credential = smsCredential;
            authType = "APP_SMS";
        } else {
            if (StringUtils.isBlank(request.getPassword())) {
                return R.fail(400, "密码不能为空");
            }
            credential = new UsernamePasswordCredential(loginPrincipal, request.getPassword());
            authType = "APP_PASSWORD";
        }

        try {
            // 2. 调用 CAS 原生认证链
            AuthenticationResult authResult = authenticationSystemSupport.finalizeAuthenticationTransaction(credential);
            if (authResult == null || authResult.getAuthentication() == null) {
                return R.fail(401, "认证失败");
            }

            Principal principal = authResult.getAuthentication().getPrincipal();
            Long serviceId = resolveBusinessServiceId(httpRequest);
            if (serviceId != null) {
                appAccessService.autoGrantOnRegister(principal.getId(), serviceId);
            }

            // 3. 创建 TGT
            TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory =
                    (TicketGrantingTicketFactory<TicketGrantingTicket>) defaultTicketFactory.get(TicketGrantingTicket.class);
            TicketGrantingTicket tgt = tgtFactory.create(authResult.getAuthentication(), null);
            ticketRegistry.addTicket(tgt);

            // 4. 更新最近登录信息
            try {
                userMapper.updateLoginInfo(principal.getId(), clientIp, userAgent, deviceId, authType);
            } catch (Exception e) {
                LOGGER.warn("更新用户登录信息失败: userId={}", principal.getId(), e);
            }

            // 5. 签发 refreshToken
            String refreshToken = UUID.randomUUID().toString().replace("-", "");
            long refreshTtl = tokenTtlProperties.getRefreshTokenTtlSeconds();
            redisTemplate.opsForValue().set(
                    CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken,
                    principal.getId(),
                    refreshTtl,
                    TimeUnit.SECONDS);

            return R.ok(buildTokenResponse(tgt.getId(), refreshToken, principal));
        } catch (BusinessException e) {
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("APP 登录失败", e);
            return R.fail(500, "登录失败");
        }
    }

    /**
     * APP 登出。
     *
     * @param request 登出请求
     * @param httpRequest 当前 HTTP 请求
     * @return 登出结果
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody AppLogoutRequestDTO request, HttpServletRequest httpRequest) {
        String refreshToken = request.getRefreshToken();
        String accessToken = request.getAccessToken();

        if (StringUtils.isNotBlank(refreshToken)) {
            redisTemplate.delete(CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken);
        }

        if (StringUtils.isNotBlank(accessToken)) {
            try {
                TicketGrantingTicket tgt = ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class);
                if (tgt != null) {
                    ticketRegistry.deleteTicket(accessToken);
                }
            } catch (Exception e) {
                LOGGER.debug("APP 登出时 TGT 已失效: {}", accessToken);
            }
        }

        return R.ok(null);
    }

    /**
     * 构建统一的 Token 响应。
     *
     * @param accessToken accessToken/TGT
     * @param refreshToken refreshToken
     * @param principal 用户主体
     * @return Token 响应
     */
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

    /**
     * 获取业务系统 serviceId。
     *
     * @param request 当前请求
     * @return serviceId，未配置则返回 null
     */
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

    /**
     * 获取客户端真实 IP。
     *
     * @param request 当前请求
     * @return 客户端 IP
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
}
