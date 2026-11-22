package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.persistence.entity.Realm;
import org.springframework.stereotype.Component;

/**
 * 身份域实体/模型转换器。
 * <p>令牌与会话时长未配置时按默认值回落;主题为空表示跟随全局。</p>
 */
@Component
public class RealmConverter {

    /**
     * 实体转换为领域模型。
     *
     * @param realm 身份域实体
     * @return 身份域模型;入参为 null 时返回 null
     */
    public RealmModel toModel(Realm realm) {
        if (realm == null) {
            return null;
        }
        return new RealmModel(
                realm.getId(),
                realm.getRealmKey(),
                realm.getRealmName(),
                Boolean.TRUE.equals(realm.getEnabled()),
                Boolean.TRUE.equals(realm.getIsDefault()),
                realm.getAccessTokenTtl() == null ? 300L : realm.getAccessTokenTtl(),
                realm.getRefreshTokenTtl() == null ? 1800L : realm.getRefreshTokenTtl(),
                realm.getSsoSessionIdle() == null ? 1800L : realm.getSsoSessionIdle(),
                realm.getSsoSessionMax() == null ? 36000L : realm.getSsoSessionMax(),
                // 空/脏值回落到登录名语义:与 DDL 默认值一致,历史库升级后行为不变
                SubjectMode.parse(realm.getSubjectMode()),
                // 空串等同未配置:域级主题选择"跟随全局"
                realm.getTheme() == null || realm.getTheme().isBlank() ? null : realm.getTheme().trim()
        );
    }
}
