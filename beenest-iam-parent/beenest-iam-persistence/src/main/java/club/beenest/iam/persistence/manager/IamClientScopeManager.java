package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.ClientScope;
import club.beenest.iam.persistence.entity.ClientScopeMapping;
import club.beenest.iam.persistence.mapper.ClientScopeMapper;
import club.beenest.iam.persistence.mapper.ClientScopeMappingMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端作用域存储(iam_client_scope / iam_client_scope_mapping)。
 * <p>删除作用域时级联清理客户端关联;客户端作用域分配为覆盖式写入,自带事务。</p>
 */
@Component
public class IamClientScopeManager {

    private final ClientScopeMapper clientScopeMapper;
    private final ClientScopeMappingMapper clientScopeMappingMapper;

    public IamClientScopeManager(ClientScopeMapper clientScopeMapper,
                                 ClientScopeMappingMapper clientScopeMappingMapper) {
        this.clientScopeMapper = clientScopeMapper;
        this.clientScopeMappingMapper = clientScopeMappingMapper;
    }

    // ===== 作用域 CRUD =====

    /**
     * 按主键查询。
     *
     * @param id 作用域主键
     * @return 作用域;不存在返回 null
     */
    public ClientScope getById(Long id) {
        return clientScopeMapper.selectById(id);
    }

    /**
     * 按域与作用域键查询。
     *
     * @param realmId 身份域主键
     * @param scopeKey 作用域键
     * @return 作用域;不存在返回 null
     */
    public ClientScope getByKey(Long realmId, String scopeKey) {
        return clientScopeMapper.selectByRealmIdAndKey(realmId, scopeKey);
    }

    /**
     * 查询域内全部作用域。
     *
     * @param realmId 身份域主键
     * @return 作用域列表
     */
    public List<ClientScope> listByRealm(Long realmId) {
        return clientScopeMapper.selectByRealmId(realmId);
    }

    /**
     * 新建作用域。
     *
     * @param clientScope 作用域实体
     * @return 受影响行数
     */
    public int create(ClientScope clientScope) {
        return clientScopeMapper.insert(clientScope);
    }

    /**
     * 更新非空字段。
     *
     * @param clientScope 作用域实体,需含主键
     * @return 受影响行数
     */
    public int update(ClientScope clientScope) {
        return clientScopeMapper.updateById(clientScope);
    }

    /**
     * 逻辑删除作用域,级联清理客户端关联。
     *
     * @param id 作用域主键
     * @return 受影响行数
     */
    @Transactional
    public int remove(Long id) {
        for (ClientScopeMapping mapping : clientScopeMappingMapper.selectByScopeId(id)) {
            clientScopeMappingMapper.deleteByClientIdAndScopeId(mapping.getClientId(), id);
        }
        return clientScopeMapper.softDeleteById(id);
    }

    // ===== 客户端作用域分配 =====

    /**
     * 查询客户端已分配的作用域主键。
     *
     * @param clientId 客户端主键
     * @return 作用域主键列表
     */
    public List<Long> listScopeIdsByClient(Long clientId) {
        return clientScopeMappingMapper.selectByClientId(clientId).stream()
                .map(ClientScopeMapping::getScopeId)
                .toList();
    }

    /**
     * 清理客户端的全部作用域分配。
     * <p>作用域分配是客户端自身的配置,客户端删除时随之一并清理,不留悬空关联。</p>
     *
     * @param clientId 客户端主键
     * @return 受影响行数
     */
    public int removeClientScopes(Long clientId) {
        return clientScopeMappingMapper.deleteByClientId(clientId);
    }

    /**
     * 覆盖式分配客户端作用域,以目标集合为准增删关联。
     *
     * @param realmId 身份域主键
     * @param clientId 客户端主键
     * @param scopeIds 目标作用域主键,为空表示清空
     */
    @Transactional
    public void assignClientScopes(Long realmId, Long clientId, List<Long> scopeIds) {
        Set<Long> current = new HashSet<>();
        for (ClientScopeMapping mapping : clientScopeMappingMapper.selectByClientId(clientId)) {
            current.add(mapping.getScopeId());
        }
        Set<Long> target = new HashSet<>(scopeIds == null ? List.of() : scopeIds);
        for (Long scopeId : target) {
            if (!current.contains(scopeId)) {
                ClientScopeMapping mapping = new ClientScopeMapping();
                mapping.setRealmId(realmId);
                mapping.setClientId(clientId);
                mapping.setScopeId(scopeId);
                clientScopeMappingMapper.insert(mapping);
            }
        }
        for (Long scopeId : current) {
            if (!target.contains(scopeId)) {
                clientScopeMappingMapper.deleteByClientIdAndScopeId(clientId, scopeId);
            }
        }
    }
}
