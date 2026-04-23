package org.apereo.cas.beenest.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.authn.credential.AppTokenCredential;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.common.util.CasAttributeUtils;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.TokenRefreshRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.Principal;
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
 * 统一 Token 续期控制器。
 * <p>
 * 所有 Bearer Token 客户端共享同一 refresh 入口，避免重复维护多个几乎相同的端点。
 */
@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class TokenRefreshController {

    private final AuthenticationSystemSupport authenticationSystemSupport;
    private final TicketRegistry ticketRegistry;
    private final DefaultTicketFactory defaultTicketFactory;
    private final AuthAuditService auditService;
    private final UnifiedUserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final TokenTtlProperties tokenTtlProperties;

    /**
     * 统一 refresh 端点。
     *
     * @param request 刷新请求
     * @param httpRequest 当前 HTTP 请求
     * @return 新的 accessToken / refreshToken
     */
    @PostMapping("/refresh")
    public R<TokenResponseDTO> refresh(@RequestBody TokenRefreshRequestDTO request,
                                       HttpServletRequest httpRequest) {
        String oldRefreshToken = request.getRefreshToken();
        if (StringUtils.isBlank(oldRefreshToken)) {
            return R.fail(400, "refreshToken 不能为空");
        }

        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        // 1. 先消费旧 refreshToken，兼容迁移期间的旧前缀数据
        String userId = consumeRefreshToken(oldRefreshToken);
        if (userId == null) {
            auditService.record(null, null, "TOKEN_REFRESH", "FAILED",
                    "refreshToken 已过期或已被使用", clientIp, userAgent, null, null, "tokenRefresh");
            return R.fail(401, "refreshToken 已过期或已被使用");
        }

        try {
            // 2. 通过认证引擎创建新 TGT（使用 preValidatedUserId 跳过重新认证）
            AppTokenCredential credential = new AppTokenCredential();
            credential.setLoginMethod("refresh");
            credential.setPreValidatedUserId(userId);
            credential.setRefreshToken(oldRefreshToken);

            AuthenticationResult authResult = authenticationSystemSupport.finalizeAuthenticationTransaction(credential);
            if (authResult == null || authResult.getAuthentication() == null) {
                auditService.record(userId, userId, "TOKEN_REFRESH", "FAILED", "认证结果为空",
                        clientIp, userAgent, null, null, "tokenRefresh");
                return R.fail(401, "Token 刷新失败");
            }

            Principal principal = authResult.getAuthentication().getPrincipal();

            // 3. 创建新 TGT
            TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory =
                    (TicketGrantingTicketFactory<TicketGrantingTicket>) defaultTicketFactory.get(TicketGrantingTicket.class);
            TicketGrantingTicket tgt = tgtFactory.create(authResult.getAuthentication(), null);
            ticketRegistry.addTicket(tgt);
            LOGGER.info("Token 续期 TGT 签发成功: userId={}, tgtId={}...",
                    principal.getId(), tgt.getId().substring(0, Math.min(tgt.getId().length(), 16)));

            // 4. 更新用户最近登录信息
            try {
                userMapper.updateLoginInfo(principal.getId(), clientIp, userAgent, null, "TOKEN_REFRESH");
            } catch (Exception e) {
                LOGGER.warn("更新用户登录信息失败: userId={}", principal.getId(), e);
            }

            // 5. 生成新的 refreshToken，统一存入同一个前缀
            String newRefreshToken = generateRefreshToken(principal.getId());

            // 6. 记录审计日志
            auditService.record(principal.getId(), principal.getId(), "TOKEN_REFRESH", "SUCCESS", null,
                    clientIp, userAgent, null, null, "tokenRefresh");

            // 7. 构建返回数据
            return R.ok(buildTokenResponse(tgt.getId(), newRefreshToken, principal));

        } catch (BusinessException e) {
            auditService.record(userId, userId, "TOKEN_REFRESH", "FAILED", e.getMessage(),
                    clientIp, userAgent, null, null, "tokenRefresh");
            return R.fail(e.getCode(), e.getMessage());
        } catch (Throwable e) {
            LOGGER.error("Token 刷新失败: userId={}", userId, e);
            auditService.record(userId, userId, "TOKEN_REFRESH", "FAILED", e.getMessage(),
                    clientIp, userAgent, null, null, "tokenRefresh");
            return R.fail(500, "Token 刷新失败");
        }
    }

    /**
     * 消费旧 refreshToken。
     * <p>
     * 优先使用统一前缀；如果线上还有旧数据，则继续兼容历史前缀，
     * 这样可以平滑完成迁移。
     *
     * @param refreshToken 刷新令牌
     * @return userId，未命中返回 null
     */
    private String consumeRefreshToken(String refreshToken) {
        String userId = consumeByPrefix(CasConstant.REDIS_REFRESH_TOKEN_PREFIX, refreshToken);
        if (userId != null) {
            return userId;
        }
        userId = consumeByPrefix(CasConstant.REDIS_APP_TOKEN_PREFIX, refreshToken);
        if (userId != null) {
            return userId;
        }
        return consumeByPrefix(CasConstant.REDIS_MINIAPP_TOKEN_PREFIX, refreshToken);
    }

    /**
     * 按指定前缀消费 refreshToken。
     *
     * @param prefix Redis 前缀
     * @param refreshToken 刷新令牌
     * @return userId，未命中返回 null
     */
    private String consumeByPrefix(String prefix, String refreshToken) {
        String key = prefix + "refresh:" + refreshToken;
        if (tokenTtlProperties.isRefreshTokenRotation()) {
            return redisTemplate.opsForValue().getAndDelete(key);
        }
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 生成新的 refreshToken 并写入统一前缀。
     *
     * @param userId 用户 ID
     * @return 新 refreshToken
     */
    private String generateRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        long ttl = tokenTtlProperties.getRefreshTokenTtlSeconds();
        String key = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken;
        redisTemplate.opsForValue().set(key, userId, ttl, TimeUnit.SECONDS);
        return refreshToken;
    }

    /**
     * 从认证结果构建 Token 响应。
     *
     * @param accessToken 访问令牌
     * @param refreshToken 刷新令牌
     * @param principal 用户主体
     * @return 统一 Token 响应
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
     * 获取客户端真实 IP。
     *
     * @param request HTTP 请求
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
