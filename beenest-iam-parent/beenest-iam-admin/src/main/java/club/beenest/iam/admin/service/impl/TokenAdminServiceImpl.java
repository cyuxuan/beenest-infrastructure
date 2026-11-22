package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AdminTokenResponse;
import club.beenest.iam.admin.service.TokenAdminService;
import club.beenest.iam.authz.authorization.RedisOAuth2AuthorizationService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 已签发令牌管理服务实现。
 * <p>授权记录存放在 Redis(协议运行时态),不属于任何单表:按用户列举走
 * 用户维索引,吊销走授权存储自身的 remove,保持与协议端点同一事实源。</p>
 */
@Service
public class TokenAdminServiceImpl implements TokenAdminService {

    private final RedisOAuth2AuthorizationService authorizationService;
    private final IamUserManager userManager;
    private final ClientProvider clientProvider;

    public TokenAdminServiceImpl(RedisOAuth2AuthorizationService authorizationService,
                                 IamUserManager userManager, ClientProvider clientProvider) {
        this.authorizationService = authorizationService;
        this.userManager = userManager;
        this.clientProvider = clientProvider;
    }

    @Override
    public PageResult<AdminTokenResponse> page(String username, String clientId, int pageNum, int pageSize) {
        // 1. 登录名必填:按用户收敛范围,避免退化为全量列举
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "查询令牌必须指定登录名");
        }
        // 2. 用户必须属于当前域(跨域按不存在处理)
        Long realmId = currentRealmId();
        User user = userManager.getByUsername(realmId, username);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + username);
        }
        // 3. 取该用户全部有效授权,按客户端标识过滤
        List<OAuth2Authorization> authorizations = new ArrayList<>(
                authorizationService.listByUser(user.getId()));
        if (clientId != null && !clientId.isBlank()) {
            authorizations.removeIf(a -> !clientId.equals(a.getRegisteredClientId()));
        }
        // 4. 内存分页:单用户授权数量有限(个位数到数十),不值得引入存储分页
        int total = authorizations.size();
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<AdminTokenResponse> records = authorizations.subList(from, to).stream()
                .map(this::toResponse)
                .toList();
        int pages = (int) Math.ceil((double) total / pageSize);
        return new PageResult<>(pageNum, pageSize, total, pages, records);
    }

    @Override
    public void revoke(String authorizationId) {
        // 1. 授权必须存在且其主体属于当前域,防止跨域吊销
        requireAuthorizationInRealm(authorizationId);
        // 2. 走授权存储自身的删除:正向键、反向索引、用户维索引一并清理,
        //    刷新令牌随之失效(刷新时授权已不存在)
        boolean removed = authorizationService.removeById(authorizationId);
        if (!removed) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权不存在或已过期: " + authorizationId);
        }
    }

    @Override
    public int revokeByUser(Long userId) {
        // 1. 用户必须属于当前域
        requireUserInRealm(userId);
        // 2. 逐条吊销(数量有限);全部撤销后刷新令牌同样失效
        List<OAuth2Authorization> authorizations = authorizationService.listByUser(userId);
        int count = 0;
        for (OAuth2Authorization authorization : authorizations) {
            if (authorizationService.removeById(authorization.getId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 校验授权存在且属于当前域。
     * <p>归属判据是注册客户端的域,授权记录本身不带域维度;
     * 跨域与不存在返回同一文案,不透露主键在别处的存在性。</p>
     *
     * @param authorizationId 授权主键
     * @return 授权对象
     * @throws BusinessException 授权不存在、已过期或不属于当前域
     */
    private OAuth2Authorization requireAuthorizationInRealm(String authorizationId) {
        OAuth2Authorization authorization = authorizationService.findById(authorizationId);
        if (authorization == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权不存在或已过期: " + authorizationId);
        }
        if (!belongsToCurrentRealm(authorization.getRegisteredClientId())) {
            // 跨域与不存在返回同一文案:不告诉调用方"这个主键在别处存在"
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权不存在: " + authorizationId);
        }
        return authorization;
    }

    /**
     * 判断注册客户端是否属于当前域。
     *
     * @param registeredClientId 注册客户端主键
     * @return 属于当前域时为 true,主键非法或客户端已删除时为 false
     */
    private boolean belongsToCurrentRealm(String registeredClientId) {
        if (registeredClientId == null || registeredClientId.isBlank()) {
            return false;
        }
        try {
            return clientProvider.findById(Long.valueOf(registeredClientId))
                    .map(client -> currentRealmId().equals(client.realmId()))
                    .orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 读取属于当前域的用户。
     *
     * @param userId 用户主键
     * @return 用户
     * @throws BusinessException 用户不存在或不属于当前域
     */
    private User requireUserInRealm(Long userId) {
        User user = userManager.getById(userId);
        if (user == null || !currentRealmId().equals(user.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
        return user;
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return CurrentUserHolder.require().getRealmId();
    }

    /**
     * 授权记录转响应,不暴露任何令牌值。
     *
     * @param authorization 授权记录
     * @return 授权元信息响应
     */
    private AdminTokenResponse toResponse(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AccessToken> access =
                authorization.getToken(OAuth2AccessToken.class);
        OAuth2Authorization.Token<OAuth2RefreshToken> refresh =
                authorization.getToken(OAuth2RefreshToken.class);
        Instant issuedAt = access != null ? access.getToken().getIssuedAt() : null;
        Instant expiresAt = null;
        if (refresh != null) {
            expiresAt = refresh.getToken().getExpiresAt();
        } else if (access != null) {
            expiresAt = access.getToken().getExpiresAt();
        }
        return new AdminTokenResponse(
                authorization.getId(),
                authorization.getRegisteredClientId(),
                authorization.getPrincipalName(),
                authorization.getAuthorizationGrantType().getValue(),
                authorization.getAuthorizedScopes(),
                access != null,
                refresh != null,
                issuedAt,
                expiresAt);
    }
}
