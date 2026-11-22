package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 域级邮件模板覆盖实体(iam_mail_template)。
 * <p>每个模板每域至多一条覆盖记录;没有覆盖时使用 classpath 内置模板。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MailTemplateOverride extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 模板编码,与内置模板文件名对应 */
    private String templateCode;
    /** 覆盖后的模板原文(HTML,{@code {{变量名}}} 为占位) */
    private String content;
}
