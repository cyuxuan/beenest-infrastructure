package club.beenest.iam.persistence.manager;

import club.beenest.iam.core.model.ClientSessionModel;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.persistence.entity.ClientSession;
import club.beenest.iam.persistence.mapper.ClientSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 客户端会话存储(iam_client_session)。
 * <p>写入为先查后插,唯一索引兜底并发重复写;同一会话对同一客户端只保留一行。</p>
 */
@Component
public class IamClientSessionManager implements ClientSessionStore {

    private static final Logger log = LoggerFactory.getLogger(IamClientSessionManager.class);

    private final ClientSessionMapper clientSessionMapper;

    public IamClientSessionManager(ClientSessionMapper clientSessionMapper) {
        this.clientSessionMapper = clientSessionMapper;
    }

    /** 记录客户端会话,已存在时直接复用;唯一索引冲突按已存在处理。 */
    @Override
    public ClientSessionModel record(Long realmId, Long userSessionId, Long userId, Long clientId) {
        // 1. 已存在直接复用
        ClientSession existing = clientSessionMapper.selectBySessionAndClient(userSessionId, clientId);
        if (existing != null) {
            return toModel(existing, realmId, userId, clientId);
        }
        // 2. 并发下唯一索引冲突按已存在处理
        ClientSession entity = new ClientSession();
        entity.setRealmId(realmId);
        entity.setUserSessionId(userSessionId);
        entity.setClientId(clientId);
        try {
            clientSessionMapper.insert(entity);
            return toModel(entity, realmId, userId, clientId);
        } catch (DuplicateKeyException e) {
            log.debug("客户端会话并发写入,按已存在处理: session={}, client={}", userSessionId, clientId);
            ClientSession current = clientSessionMapper.selectBySessionAndClient(userSessionId, clientId);
            return current == null ? null : toModel(current, realmId, userId, clientId);
        }
    }

    /** 按 sid 查询客户端会话,域或 sid 为空时返回空列表。 */
    @Override
    public List<ClientSessionModel> listBySid(Long realmId, String sid) {
        if (realmId == null || sid == null || sid.isBlank()) {
            return List.of();
        }
        return clientSessionMapper.selectBySid(realmId, sid).stream()
                .map(row -> new ClientSessionModel(row.getId(), row.getRealmId(), row.getUserSessionId(),
                        row.getSid(), row.getUserId(), row.getClientId(), row.getClientKey()))
                .toList();
    }

    /** 按 sid 逻辑删除客户端会话,sid 为空时返回 0。 */
    @Override
    public int closeBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return 0;
        }
        return clientSessionMapper.softDeleteBySid(sid);
    }

    /** 按用户与客户端查询会话记录,入参不完整时返回空列表。 */
    @Override
    public List<ClientSessionModel> listByUserAndClient(Long realmId, Long userId, Long clientId) {
        if (realmId == null || userId == null || clientId == null) {
            return List.of();
        }
        return clientSessionMapper.selectByUserAndClient(realmId, userId, clientId).stream()
                .map(row -> new ClientSessionModel(row.getId(), row.getRealmId(), row.getUserSessionId(),
                        row.getSid(), row.getUserId(), row.getClientId(), row.getClientKey()))
                .toList();
    }

    /** 关闭某用户在一个客户端上的全部会话记录,入参不完整时返回 0。 */
    @Override
    public int closeByUserAndClient(Long realmId, Long userId, Long clientId) {
        if (realmId == null || userId == null || clientId == null) {
            return 0;
        }
        return clientSessionMapper.softDeleteByUserAndClient(realmId, userId, clientId);
    }

    /** 关闭某会话在单个客户端上的记录,sid 或客户端为空时返回 0。 */
    @Override
    public int closeBySidAndClient(String sid, Long clientId) {
        if (sid == null || sid.isBlank() || clientId == null) {
            return 0;
        }
        return clientSessionMapper.softDeleteBySidAndClient(sid, clientId);
    }

    /**
     * 实体转领域模型。
     *
     * @param entity 客户端会话实体
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @param clientId 客户端主键
     * @return 客户端会话模型
     */
    private ClientSessionModel toModel(ClientSession entity, Long realmId, Long userId, Long clientId) {
        return new ClientSessionModel(entity.getId(), realmId, entity.getUserSessionId(),
                entity.getSid(), userId, clientId, entity.getClientKey());
    }
}
