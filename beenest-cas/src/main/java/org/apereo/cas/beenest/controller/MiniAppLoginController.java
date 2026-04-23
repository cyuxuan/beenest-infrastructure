package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.authn.credential.AlipayMiniCredential;
import org.apereo.cas.beenest.authn.credential.DouyinMiniCredential;
import org.apereo.cas.beenest.authn.credential.WechatMiniCredential;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.common.util.CasAttributeUtils;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.MiniAppLoginDTO;
import org.apereo.cas.beenest.dto.MiniAppLogoutDTO;
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
import org.apereo.cas.authentication.Credential;
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
 * 小程序登录 REST 控制器
 * <p>
 * 小程序登录不走浏览器重定向流程，通过 REST 端点接收 code，
 * 构造 Credential，调用 CAS 内部认证引擎。
 * <p>
 * refreshToken 续期由统一的 {@code /refresh} 端点处理。
 * <p>
 * 登录成功返回 accessToken（TGT）+ refreshToken（用于静默续期），
 * 支持微信、抖音、支付宝三个小程序平台。
 */
@Slf4j
@RestController
@RequestMapping("/miniapp")
@RequiredArgsConstructor
public class MiniAppLoginController {

    private final AuthenticationSystemSupport authenticationSystemSupport;
    private final TicketRegistry ticketRegistry;
    private final DefaultTicketFactory defaultTicketFactory;
    private final AuthAuditService auditService;
    private final AppAccessService appAccessService;
    private final UnifiedUserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final TokenTtlProperties tokenTtlProperties;

    /**
     * 微信小程序登录
     *
     * @param dto { code, phoneCode, userType, nickname }
     * @return { accessToken, refreshToken, expiresIn, userId, attributes }
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

        return executeLogin(credential, "WECHAT", dto.getUserType(), httpRequest);
    }

    /**
     * 抖音小程序登录
     *
     * @param dto { douyinCode, userType, nickname }
     * @return { accessToken, refreshToken, expiresIn, userId, attributes }
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

        return executeLogin(credential, "DOUYIN_MINI", dto.getUserType(), httpRequest);
    }

    /**
     * 支付宝小程序登录（支持手机号一键登录）
     *
     * @param dto { authCode, phoneCode, userType, nickname }
     * @return { accessToken, refreshToken, expiresIn, userId, attributes }
     */
    @PostMapping("/alipay/login")
    public R<TokenResponseDTO> alipayLogin(@RequestBody MiniAppLoginDTO dto,
                                               HttpServletRequest httpRequest) {
        // authCode 和 phoneCode 至少需要一个
        if (StringUtils.isBlank(dto.getAuthCode()) && StringUtils.isBlank(dto.getPhoneCode())) {
            return R.fail(400, "支付宝授权码或手机号授权码不能同时为空");
        }

        AlipayMiniCredential credential = new AlipayMiniCredential(dto.getAuthCode());
        credential.setPhoneCode(dto.getPhoneCode());
        credential.setUserType(dto.getUserType());
        credential.setNickname(dto.getNickname());

        return executeLogin(credential, "ALIPAY_MINI", dto.getUserType(), httpRequest);
    }

    /**
     * 小程序登出
     * <p>
     * 删除 refreshToken + 销毁 TGT，防止已注销的 token 被复用。
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody MiniAppLogoutDTO dto,
                           HttpServletRequest httpRequest) {
        String refreshToken = dto.getRefreshToken();
        String accessToken = dto.getAccessToken();
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
                    LOGGER.info("小程序登出成功，TGT 已销毁: {}", accessToken);
                }
            } catch (Exception e) {
                LOGGER.debug("小程序登出时 TGT 不存在或已过期: {}", accessToken);
            }
        }

        // 3. 记录审计日志
        auditService.record(userId, userId, "MINIAPP_LOGOUT", "SUCCESS", null,
                clientIp, userAgent, null, null, "miniappLogout");

        return R.ok(null);
    }

    /**
     * 执行认证并签发 TGT + refreshToken
     */
    private R<TokenResponseDTO> executeLogin(Credential credential, String authType,
                                             String principal, HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        try {
            // 1. 执行认证
            AuthenticationResult authResult = authenticationSystemSupport
                    .finalizeAuthenticationTransaction(credential);

            if (authResult == null || authResult.getAuthentication() == null) {
                auditService.record(null, principal, authType, "FAILED", "认证结果为空",
                        clientIp, userAgent, null, null, authType);
                return R.fail(401, "认证失败");
            }

            Principal authPrincipal = authResult.getAuthentication().getPrincipal();

            Long serviceId = resolveBusinessServiceId(httpRequest);
            if (serviceId != null) {
                // 全新系统不考虑历史用户，登录成功后直接补齐当前业务系统授权。
                appAccessService.autoGrantOnRegister(authPrincipal.getId(), serviceId);
            }

            // 2. 创建 TGT
            TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory =
                    (TicketGrantingTicketFactory<TicketGrantingTicket>) defaultTicketFactory.get(TicketGrantingTicket.class);
            TicketGrantingTicket tgt = tgtFactory.create(authResult.getAuthentication(), null);
            ticketRegistry.addTicket(tgt);
            LOGGER.info("小程序登录 TGT 签发成功: userId={}, authType={}, tgtId={}...",
                    authPrincipal.getId(), authType, tgt.getId().substring(0, Math.min(tgt.getId().length(), 16)));

            // 3. 更新用户最近登录信息
            try {
                userMapper.updateLoginInfo(authPrincipal.getId(), clientIp, userAgent, null, authType);
            } catch (Exception e) {
                LOGGER.warn("更新用户登录信息失败: userId={}", authPrincipal.getId(), e);
            }

            // 4. 生成 refreshToken
            String refreshToken = generateRefreshToken(authPrincipal.getId());

            // 5. 记录成功审计日志
            auditService.record(authPrincipal.getId(), principal, authType, "SUCCESS", null,
                    clientIp, userAgent, null, String.valueOf(serviceId), authType);

            // 6. 构建返回数据
            return R.ok(buildTokenResponse(tgt.getId(), refreshToken, authPrincipal));

        } catch (BusinessException e) {
            auditService.record(null, principal, authType, "FAILED", e.getMessage(),
                    clientIp, userAgent, null, null, authType);
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("小程序登录失败", e);
            auditService.record(null, principal, authType, "FAILED", e.getMessage(),
                    clientIp, userAgent, null, null, authType);
            return R.fail(500, "登录失败");
        }
    }

    /**
     * 生成 refreshToken 并存入 Redis
     */
    private String generateRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        long ttl = tokenTtlProperties.getRefreshTokenTtlSeconds();
        String key = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken;
        redisTemplate.opsForValue().set(key, userId, ttl, TimeUnit.SECONDS);
        return refreshToken;
    }

    /**
     * 构建 Token 响应数据（统一格式）
     */
    private TokenResponseDTO buildTokenResponse(String accessToken, String refreshToken,
                                                Principal principal) {
        TokenResponseDTO data = new TokenResponseDTO();
        data.setAccessToken(accessToken);
        data.setTgt(accessToken);  // 兼容旧客户端
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
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
