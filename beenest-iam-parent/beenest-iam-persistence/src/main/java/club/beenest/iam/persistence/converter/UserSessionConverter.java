package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.persistence.entity.UserSession;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户会话实体/模型转换器。
 * <p>模型转实体时只填会话业务字段,主键与创建时间由库端生成。</p>
 */
@Component
public class UserSessionConverter {

    /**
     * 实体转换为领域模型。
     *
     * @param entity 会话实体
     * @return 会话模型;入参为 null 时返回 null
     */
    public UserSessionModel toModel(UserSession entity) {
        if (entity == null) {
            return null;
        }
        return new UserSessionModel(
                entity.getId(),
                entity.getRealmId(),
                entity.getSid(),
                entity.getUserId(),
                entity.getLoginType(),
                entity.getIp(),
                entity.getUserAgent(),
                entity.getState(),
                entity.getCreatedTime(),
                entity.getLastActiveTime(),
                entity.getExpiredTime()
        );
    }

    /**
     * 实体列表转换为领域模型列表。
     *
     * @param entities 会话实体列表
     * @return 会话模型列表;顺序与入参一致
     */
    public List<UserSessionModel> toModelList(List<UserSession> entities) {
        return entities.stream().map(this::toModel).toList();
    }

    /**
     * 领域模型转换为实体。
     * <p>不回填 id 与 created_time 等库端字段。</p>
     *
     * @param model 会话模型
     * @return 会话实体
     */
    public UserSession toEntity(UserSessionModel model) {
        UserSession entity = new UserSession();
        entity.setRealmId(model.realmId());
        entity.setSid(model.sid());
        entity.setUserId(model.userId());
        entity.setLoginType(model.loginType());
        entity.setIp(model.ip());
        entity.setUserAgent(model.userAgent());
        entity.setState(model.state());
        entity.setLastActiveTime(model.lastActiveTime());
        entity.setExpiredTime(model.expiredTime());
        return entity;
    }
}
