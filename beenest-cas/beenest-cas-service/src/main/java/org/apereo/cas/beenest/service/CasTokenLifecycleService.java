package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.util.CasAttributeUtils;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.DefaultAuthenticationBuilder;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketFactory;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CAS Token 生命周期服务。
 * <p>
 * 统一处理刷新场景中的 TGT 签发、refreshToken 轮换、最近登录信息更新以及登出回收逻辑。
 * <p>
 * 这样控制器只负责渠道差异和参数校验，Token 生命周期始终走同一套原生实现。
 */
@Slf4j
public class CasTokenLifecycleService {

    private final TicketRegistry ticketRegistry;
    private final DefaultTicketFactory defaultTicketFactory;
    private final StringRedisTemplate redisTemplate;
    private final UnifiedUserMapper userMapper;
    private final TokenTtlProperties tokenTtlProperties;
    private final PrincipalFactory principalFactory;

    /**
     * 构造 Token 生命周期服务。
     *
     * @param ticketRegistry 票据仓库
     * @param defaultTicketFactory 默认票据工厂
     * @param redisTemplate Redis 模板
     * @param userMapper 统一用户映射器
     * @param tokenTtlProperties Token 生命周期配置
     */
    public CasTokenLifecycleService(TicketRegistry ticketRegistry,
                                    DefaultTicketFactory defaultTicketFactory,
                                    StringRedisTemplate redisTemplate,
                                    UnifiedUserMapper userMapper,
                                    TokenTtlProperties tokenTtlProperties,
                                    PrincipalFactory principalFactory) {
        this.ticketRegistry = ticketRegistry;
        this.defaultTicketFactory = defaultTicketFactory;
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
        this.tokenTtlProperties = tokenTtlProperties;
        this.principalFactory = principalFactory;
    }

    /**
     * 认证成功后签发 TGT 和 refreshToken，并更新最近登录信息。
     *
     * @param authResult CAS 认证结果
     * @param authType 登录类型标识
     * @param clientIp 客户端 IP
     * @param userAgent 客户端 UA
     * @param deviceId 设备 ID，可为空
     * @return 统一 Token 响应
     */
    public TokenResponseDTO issueToken(AuthenticationResult authResult,
                                       String authType,
                                       String clientIp,
                                       String userAgent,
                                       String deviceId) {
        if (authResult == null || authResult.getAuthentication() == null) {
            throw new IllegalStateException("认证结果为空");
        }
        Authentication authentication = authResult.getAuthentication();
        return issueToken(authentication.getPrincipal(), authType, clientIp, userAgent, deviceId);
    }

    /**
     * 按 CAS Principal 直接签发 Token。
     *
     * @param principal CAS 主体
     * @param authType 登录类型标识
     * @param clientIp 客户端 IP
     * @param userAgent 客户端 UA
     * @param deviceId 设备 ID，可为空
     * @return 统一 Token 响应
     */
    public TokenResponseDTO issueToken(Principal principal,
                                       String authType,
                                       String clientIp,
                                       String userAgent,
                                       String deviceId) {
        Authentication authentication = new DefaultAuthenticationBuilder(principal).build();

        TicketGrantingTicket tgt;
        try {
            TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory =
                (TicketGrantingTicketFactory<TicketGrantingTicket>) defaultTicketFactory.get(TicketGrantingTicket.class);
            tgt = tgtFactory.create(authentication, null);
            ticketRegistry.addTicket(tgt);
        } catch (Throwable e) {
            throw new IllegalStateException("签发 TGT 失败", e);
        }
        LOGGER.info("签发 TGT 成功: userId={}, authType={}, tgtId={}...",
            principal.getId(), authType, tgt.getId().substring(0, Math.min(tgt.getId().length(), 16)));

        try {
            userMapper.updateLoginInfo(principal.getId(), clientIp, userAgent, deviceId, authType);
        } catch (Exception e) {
            LOGGER.warn("更新用户最近登录信息失败: userId={}", principal.getId(), e);
        }

        String refreshToken = generateRefreshToken(principal.getId());
        return buildTokenResponse(tgt.getId(), refreshToken, principal);
    }

    /**
     * 按用户 ID 直接续签 Token。
     *
     * @param userId 用户 ID
     * @param authType 登录类型标识
     * @param clientIp 客户端 IP
     * @param userAgent 客户端 UA
     * @param deviceId 设备 ID，可为空
     * @return 统一 Token 响应
     */
    public TokenResponseDTO issueTokenForUserId(String userId,
                                                String authType,
                                                String clientIp,
                                                String userAgent,
                                                String deviceId) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在");
        }
        return issueToken(buildPrincipal(user), authType, clientIp, userAgent, deviceId);
    }

    /**
     * 撤销 accessToken 和 refreshToken。
     *
     * @param accessToken 访问令牌
     * @param refreshToken 刷新令牌
     */
    public void revokeTokens(String accessToken, String refreshToken) {
        if (StringUtils.isNotBlank(refreshToken)) {
            redisTemplate.delete(CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken);
        }

        if (StringUtils.isNotBlank(accessToken)) {
            try {
                TicketGrantingTicket tgt = ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class);
                if (tgt != null) {
                    ticketRegistry.deleteTicket(accessToken);
                    LOGGER.info("TGT 已撤销: {}", accessToken);
                }
            } catch (Exception e) {
                LOGGER.debug("撤销 TGT 时忽略已过期或不存在的票据: {}", accessToken);
            }
        }
    }

    /**
     * 消费 refreshToken。
     * <p>
     * 仅消费统一前缀，避免继续保留历史兼容入口。
     *
     * @param refreshToken 刷新令牌
     * @return userId，未命中返回 null
     */
    public String consumeRefreshToken(String refreshToken) {
        return consumeByPrefix(CasConstant.REDIS_REFRESH_TOKEN_PREFIX, refreshToken);
    }

    /**
     * 生成 refreshToken 并存入统一前缀。
     *
     * @param userId 用户 ID
     * @return refreshToken
     */
    private String generateRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        long ttl = tokenTtlProperties.getRefreshTokenTtlSeconds();
        String key = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + refreshToken;
        redisTemplate.opsForValue().set(key, userId, ttl, TimeUnit.SECONDS);
        return refreshToken;
    }

    /**
     * 构建统一 Token 响应。
     *
     * @param accessToken 访问令牌
     * @param refreshToken 刷新令牌
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
     * 从用户实体构建 CAS Principal。
     *
     * @param user 统一用户
     * @return CAS Principal
     */
    private Principal buildPrincipal(UnifiedUserDO user) {
        Map<String, List<Object>> attributes = new HashMap<>();
        putAttribute(attributes, "userId", user.getUserId());
        putAttribute(attributes, "userType", user.getUserType());
        putAttribute(attributes, "identity", user.getIdentity());
        putAttribute(attributes, "source", user.getSource());
        putAttribute(attributes, "loginType", user.getLoginType());
        putAttribute(attributes, "openid", user.getOpenid());
        putAttribute(attributes, "unionid", user.getUnionid());
        putAttribute(attributes, "douyinOpenid", user.getDouyinOpenid());
        putAttribute(attributes, "douyinUnionid", user.getDouyinUnionid());
        putAttribute(attributes, "alipayUid", user.getAlipayUid());
        putAttribute(attributes, "alipayOpenid", user.getAlipayOpenid());
        putAttribute(attributes, "username", user.getUsername());
        putAttribute(attributes, "nickname", user.getNickname());
        putAttribute(attributes, "avatarUrl", user.getAvatarUrl());
        putAttribute(attributes, "phone", user.getPhone());
        putAttribute(attributes, "email", user.getEmail());
        putAttribute(attributes, "phoneVerified", user.getPhoneVerified());
        putAttribute(attributes, "emailVerified", user.getEmailVerified());
        putAttribute(attributes, "mfaEnabled", user.getMfaEnabled());
        putAttribute(attributes, "tokenVersion", user.getTokenVersion());
        putAttribute(attributes, "lastLoginTime", user.getLastLoginTime());
        putAttribute(attributes, "lastLoginIp", user.getLastLoginIp());
        putAttribute(attributes, "lastLoginUa", user.getLastLoginUa());
        putAttribute(attributes, "lastLoginDevice", user.getLastLoginDevice());

        try {
            return principalFactory.createPrincipal(user.getUserId(), attributes);
        } catch (Throwable e) {
            throw new IllegalStateException("构建用户主体失败", e);
        }
    }

    /**
     * 构造单值属性。
     *
     * @param attributes 属性容器
     * @param key 属性名
     * @param value 属性值
     */
    private void putAttribute(Map<String, List<Object>> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, List.of(value));
        }
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
}
