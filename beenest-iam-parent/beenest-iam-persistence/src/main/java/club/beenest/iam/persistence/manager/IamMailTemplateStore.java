package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.MailTemplateOverride;
import club.beenest.iam.persistence.mapper.MailTemplateMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 域级邮件模板覆盖存储(iam_mail_template)。
 * <p>覆盖是可选的:读不到即使用内置模板,因此不做缓存,改动一次立即生效。</p>
 */
@Component
public class IamMailTemplateStore {

    private final MailTemplateMapper mailTemplateMapper;

    public IamMailTemplateStore(MailTemplateMapper mailTemplateMapper) {
        this.mailTemplateMapper = mailTemplateMapper;
    }

    /**
     * 查询域内某模板的覆盖内容。
     *
     * @param realmId      身份域主键
     * @param templateCode 模板编码
     * @return 覆盖内容;未覆盖返回 null
     */
    public String findContent(Long realmId, String templateCode) {
        MailTemplateOverride override = mailTemplateMapper.selectByRealmAndCode(realmId, templateCode);
        return override == null ? null : override.getContent();
    }

    /**
     * 查询域内全部覆盖记录。
     *
     * @param realmId 身份域主键
     * @return 覆盖记录列表;无覆盖返回空列表
     */
    public List<MailTemplateOverride> listByRealm(Long realmId) {
        return mailTemplateMapper.selectByRealm(realmId);
    }

    /**
     * 保存模板覆盖:已有记录则更新内容,否则插入。
     *
     * @param realmId      身份域主键
     * @param templateCode 模板编码
     * @param content      模板原文
     */
    public void save(Long realmId, String templateCode, String content) {
        MailTemplateOverride existing = mailTemplateMapper.selectByRealmAndCode(realmId, templateCode);
        if (existing != null) {
            existing.setContent(content);
            mailTemplateMapper.updateContent(existing);
            return;
        }
        MailTemplateOverride override = new MailTemplateOverride();
        override.setRealmId(realmId);
        override.setTemplateCode(templateCode);
        override.setContent(content);
        mailTemplateMapper.insert(override);
    }

    /**
     * 删除模板覆盖,使该模板回落到内置版本。
     *
     * @param realmId      身份域主键
     * @param templateCode 模板编码
     * @return 受影响行数;未覆盖时为 0
     */
    public int reset(Long realmId, String templateCode) {
        return mailTemplateMapper.deleteByRealmAndCode(realmId, templateCode);
    }
}
