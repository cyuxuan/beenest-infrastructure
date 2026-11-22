package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.ConsentRecordResponse;
import club.beenest.iam.admin.service.ConsentAdminService;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.authorization.RedisOAuth2AuthorizationConsentService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权同意管理实现。
 * <p>同意记录存放在 Redis;列举与撤销按客户端所属域和登录名双重校验域归属,
 * 对外统一暴露 client_key。</p>
 */
@Service
public class ConsentAdminServiceImpl implements ConsentAdminService {

    private final RedisOAuth2AuthorizationConsentService consentService;
    private final ApplicationAccessRevocationService revocationService;
    private final IamUserManager userManager;
    private final ClientProvider clientProvider;

    public ConsentAdminServiceImpl(RedisOAuth2AuthorizationConsentService consentService,
                                   ApplicationAccessRevocationService revocationService,
                                   IamUserManager userManager,
                                   ClientProvider clientProvider) {
        this.consentService = consentService;
        this.revocationService = revocationService;
        this.userManager = userManager;
        this.clientProvider = clientProvider;
    }

    @Override
    public List<ConsentRecordResponse> list(String principalName, String clientId) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        Map<String, Map<String, Set<String>>> all = consentService.listAll(principalName);
        List<ConsentRecordResponse> records = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> clientEntry : all.entrySet()) {
            String registeredClientId = clientEntry.getKey();
            // 1. 域隔离的第一道:客户端必须属于当前域
            if (!belongsToRealm(registeredClientId, realmId)) {
                continue;
            }
            String clientKey = toClientKey(registeredClientId);
            // 2. 客户端过滤:client_key 与内部主键都接受
            if (clientId != null && !clientId.isBlank()
                    && !clientId.equals(clientKey) && !clientId.equals(registeredClientId)) {
                continue;
            }
            for (Map.Entry<String, Set<String>> userEntry : clientEntry.getValue().entrySet()) {
                String username = userEntry.getKey();
                // 3. 域隔离的第二道:登录名必须能在当前域找到
                User user = userManager.getByUsername(realmId, username);
                if (user == null) {
                    continue;
                }
                records.add(new ConsentRecordResponse(clientKey, username, userEntry.getValue()));
            }
        }
        return records;
    }

    @Override
    public void revoke(String clientId, String principalName) {
        // 1. 登录名必须属于当前域,防止跨域撤销
        Long realmId = CurrentUserHolder.require().getRealmId();
        User user = userManager.getByUsername(realmId, principalName);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + principalName);
        }
        // 2. 撤销同意;客户端不在当前域时按不存在处理
        String registeredClientId = requireRegisteredClientId(realmId, clientId);
        if (!consentService.revoke(registeredClientId, principalName)) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "同意记录不存在: " + clientId + " / " + principalName);
        }
        // 3. 已签发的令牌一并收回:同意只影响授权端点,不删授权记录的话第三方仍可续期
        revocationService.revokeClientGrant(realmId, user.getId(), Long.valueOf(registeredClientId));
    }

    /**
     * 判断注册客户端主键是否属于指定域。
     *
     * @param registeredClientId 注册客户端主键
     * @param realmId            身份域主键
     * @return 属于指定域时为 true,主键非法或客户端已删除时为 false
     */
    private boolean belongsToRealm(String registeredClientId, Long realmId) {
        if (registeredClientId == null || registeredClientId.isBlank()) {
            return false;
        }
        try {
            return clientProvider.findById(Long.valueOf(registeredClientId))
                    .map(client -> realmId.equals(client.realmId()))
                    .orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 注册客户端主键转 client_key,查不到时原样返回。
     *
     * @param registeredClientId 注册客户端主键
     * @return 客户端标识
     */
    private String toClientKey(String registeredClientId) {
        try {
            return clientProvider.findById(Long.valueOf(registeredClientId))
                    .map(ClientModel::clientKey)
                    .orElse(registeredClientId);
        } catch (NumberFormatException e) {
            return registeredClientId;
        }
    }

    /**
     * client_key 转注册客户端主键,客户端必须属于当前域。
     * <p>入参也接受内部主键。</p>
     *
     * @param realmId  身份域主键
     * @param clientId client_key 或内部主键
     * @return 注册客户端主键
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    private String requireRegisteredClientId(Long realmId, String clientId) {
        // 1. 先按 client_key 查,再按主键查
        ClientModel client = clientProvider.findByClientKey(realmId, clientId)
                .orElseGet(() -> findByIdInRealm(realmId, clientId));
        if (client == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在: " + clientId);
        }
        return String.valueOf(client.id());
    }

    /**
     * 按内部主键查当前域客户端。
     *
     * @param realmId  身份域主键
     * @param clientId 客户端主键字符串
     * @return 客户端,主键非法、不存在或跨域时为 null
     */
    private ClientModel findByIdInRealm(Long realmId, String clientId) {
        try {
            return clientProvider.findById(Long.valueOf(clientId))
                    .filter(client -> realmId.equals(client.realmId()))
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
