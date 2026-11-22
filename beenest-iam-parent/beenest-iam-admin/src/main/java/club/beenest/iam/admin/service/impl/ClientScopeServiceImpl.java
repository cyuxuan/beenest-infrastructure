package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.ClientScopeCreateRequest;
import club.beenest.iam.admin.dto.ClientScopeResponse;
import club.beenest.iam.admin.dto.ClientScopeUpdateRequest;
import club.beenest.iam.admin.service.ClientScopeService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.persistence.entity.ClientScope;
import club.beenest.iam.persistence.manager.IamClientScopeManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客户端作用域管理实现。
 * <p>作用域限定在当前身份域且键域内唯一;客户端绑定只接受本域作用域并整体覆盖。</p>
 */
@Service
public class ClientScopeServiceImpl implements ClientScopeService {

    private final IamClientScopeManager clientScopeManager;
    private final ClientProvider clientProvider;

    public ClientScopeServiceImpl(IamClientScopeManager clientScopeManager, ClientProvider clientProvider) {
        this.clientScopeManager = clientScopeManager;
        this.clientProvider = clientProvider;
    }

    @Override
    public List<ClientScopeResponse> list() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return clientScopeManager.listByRealm(realmId).stream().map(this::toResponse).toList();
    }

    @Override
    public ClientScopeResponse get(Long id) {
        return toResponse(requireInRealm(id));
    }

    @Override
    public ClientScopeResponse create(ClientScopeCreateRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        if (clientScopeManager.getByKey(realmId, request.scopeKey()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "作用域键已存在: " + request.scopeKey());
        }
        ClientScope scope = new ClientScope();
        scope.setRealmId(realmId);
        scope.setScopeKey(request.scopeKey());
        scope.setScopeName(request.scopeName());
        scope.setIsDefault(Boolean.TRUE.equals(request.isDefault()));
        clientScopeManager.create(scope);
        return toResponse(clientScopeManager.getById(scope.getId()));
    }

    @Override
    public ClientScopeResponse update(Long id, ClientScopeUpdateRequest request) {
        ClientScope scope = requireInRealm(id);
        if (request.scopeName() != null) {
            scope.setScopeName(request.scopeName());
        }
        if (request.isDefault() != null) {
            scope.setIsDefault(request.isDefault());
        }
        clientScopeManager.update(scope);
        return toResponse(clientScopeManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        requireInRealm(id);
        clientScopeManager.remove(id);
    }

    @Override
    public List<Long> listClientScopeIds(Long clientId) {
        requireClientInRealm(clientId);
        return clientScopeManager.listScopeIdsByClient(clientId);
    }

    @Override
    public void assignClientScopes(Long clientId, List<Long> scopeIds) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        requireClientInRealm(clientId);
        // 作用域必须全部属于当前域
        for (Long scopeId : scopeIds) {
            requireInRealm(scopeId);
        }
        clientScopeManager.assignClientScopes(realmId, clientId, scopeIds);
    }

    /**
     * 校验客户端存在且属于当前域。
     *
     * @param clientId 客户端主键
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    private void requireClientInRealm(Long clientId) {
        ClientModel client = clientProvider.findById(clientId).orElse(null);
        if (client == null || !CurrentUserHolder.require().getRealmId().equals(client.realmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在: " + clientId);
        }
    }

    /**
     * 读取属于当前域的作用域。
     *
     * @param id 作用域主键
     * @return 客户端作用域
     * @throws BusinessException 作用域不存在或不属于当前域
     */
    private ClientScope requireInRealm(Long id) {
        ClientScope scope = clientScopeManager.getById(id);
        if (scope == null || !CurrentUserHolder.require().getRealmId().equals(scope.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端作用域不存在: " + id);
        }
        return scope;
    }

    /**
     * 作用域转响应。
     *
     * @param scope 客户端作用域
     * @return 作用域响应
     */
    private ClientScopeResponse toResponse(ClientScope scope) {
        return new ClientScopeResponse(scope.getId(), scope.getScopeKey(), scope.getScopeName(),
                scope.getIsDefault(), scope.getCreatedTime());
    }
}
