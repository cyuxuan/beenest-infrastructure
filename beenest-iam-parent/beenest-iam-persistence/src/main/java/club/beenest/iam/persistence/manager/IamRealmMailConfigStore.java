package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.RealmMailConfig;
import club.beenest.iam.persistence.mapper.RealmMailConfigMapper;
import org.springframework.stereotype.Component;

/**
 * 域级发信配置存储(iam_realm_mail_config,每域一行)。
 * <p>发信路径每次读库:该表每域至多一行,而配置变更需要立即生效,
 * 缓存省下的开销抵不上"改完还在用旧 SMTP"的排查成本。</p>
 */
@Component
public class IamRealmMailConfigStore {

    private final RealmMailConfigMapper mailConfigMapper;

    public IamRealmMailConfigStore(RealmMailConfigMapper mailConfigMapper) {
        this.mailConfigMapper = mailConfigMapper;
    }

    /**
     * 查询域发信配置。
     *
     * @param realmId 身份域主键
     * @return 配置;未配置返回 null
     */
    public RealmMailConfig getConfig(Long realmId) {
        return mailConfigMapper.selectByRealmId(realmId);
    }

    /**
     * 确保域存在配置行,不存在时按缺省值插入。
     *
     * @param realmId 身份域主键
     * @return 配置行
     */
    public RealmMailConfig ensureConfig(Long realmId) {
        RealmMailConfig existing = mailConfigMapper.selectByRealmId(realmId);
        if (existing != null) {
            return existing;
        }
        RealmMailConfig config = new RealmMailConfig();
        config.setRealmId(realmId);
        mailConfigMapper.insert(config);
        return mailConfigMapper.selectByRealmId(realmId);
    }

    /**
     * 更新配置。
     *
     * @param config 配置实体,需含主键
     * @return 受影响行数
     */
    public int updateConfig(RealmMailConfig config) {
        return mailConfigMapper.updateById(config);
    }
}
