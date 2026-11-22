package club.beenest.iam.persistence.manager;

import club.beenest.iam.core.model.LogoutBroadcastModel;
import club.beenest.iam.core.spi.provider.LogoutBroadcastStore;
import club.beenest.iam.persistence.entity.LogoutBroadcast;
import club.beenest.iam.persistence.mapper.LogoutBroadcastMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单点登出广播记录存储(iam_logout_broadcast)。
 * <p>失败原因按 500 字符截断;最近记录查询条数限定在 1 到 200。</p>
 */
@Component
public class IamLogoutBroadcastManager implements LogoutBroadcastStore {

    private final LogoutBroadcastMapper broadcastMapper;

    public IamLogoutBroadcastManager(LogoutBroadcastMapper broadcastMapper) {
        this.broadcastMapper = broadcastMapper;
    }

    @Override
    public LogoutBroadcastModel insert(LogoutBroadcastModel model) {
        LogoutBroadcast entity = new LogoutBroadcast();
        entity.setRealmId(model.realmId());
        entity.setSid(model.sid());
        entity.setUserId(model.userId());
        entity.setClientId(model.clientId());
        entity.setClientKey(model.clientKey());
        entity.setTargetUri(model.targetUri());
        entity.setState(model.state());
        broadcastMapper.insert(entity);
        return toModel(entity);
    }

    /** 更新投递结果,失败原因超过 500 字符时截断。 */
    @Override
    public void updateResult(Long id, String state, Integer httpStatus, String errorMessage) {
        // 失败原因列宽 500:超长会撑爆列宽导致整条更新失败,这里先截断
        broadcastMapper.updateResult(id, state, httpStatus, truncate(errorMessage, 500));
    }

    @Override
    public List<LogoutBroadcastModel> listBySid(Long realmId, String sid) {
        return broadcastMapper.selectBySid(realmId, sid).stream().map(this::toModel).toList();
    }

    /** 最近若干条广播记录,条数限定在 1 到 200。 */
    @Override
    public List<LogoutBroadcastModel> listRecent(Long realmId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return broadcastMapper.selectRecent(realmId, capped).stream().map(this::toModel).toList();
    }

    /**
     * 实体转领域模型。
     *
     * @param entity 广播记录实体
     * @return 广播记录模型
     */
    private LogoutBroadcastModel toModel(LogoutBroadcast entity) {
        return new LogoutBroadcastModel(entity.getId(), entity.getRealmId(), entity.getSid(),
                entity.getUserId(), entity.getClientId(), entity.getClientKey(), entity.getTargetUri(),
                entity.getState(), entity.getHttpStatus(), entity.getErrorMessage(),
                entity.getCreatedTime());
    }

    /**
     * 截断超长文本。
     *
     * @param value 原文本
     * @param max 最大长度
     * @return 截断后的文本;原文本为 null 或未超长时原样返回
     */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
