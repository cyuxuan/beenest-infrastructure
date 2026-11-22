package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.MailTemplateOverride;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 域级邮件模板覆盖 Mapper(iam_mail_template)。
 * <p>覆盖是可选记录:没有记录即使用内置模板,逻辑删除后立即回落到内置模板。</p>
 */
public interface MailTemplateMapper {

    /**
     * 按域与模板编码查询未删除的覆盖记录。
     *
     * @param realmId      身份域主键
     * @param templateCode 模板编码
     * @return 覆盖记录;未覆盖返回 null
     */
    MailTemplateOverride selectByRealmAndCode(@Param("realmId") Long realmId,
                                              @Param("templateCode") String templateCode);

    /**
     * 查询域内全部未删除的覆盖记录。
     *
     * @param realmId 身份域主键
     * @return 覆盖记录列表;无覆盖返回空列表
     */
    List<MailTemplateOverride> selectByRealm(@Param("realmId") Long realmId);

    /**
     * 插入覆盖记录,deleted 固定写入 FALSE。
     *
     * @param template 覆盖记录
     * @return 受影响行数;主键回填至 id
     */
    int insert(MailTemplateOverride template);

    /**
     * 按主键更新模板内容并刷新更新时间。
     *
     * @param template 覆盖记录,id 必填
     * @return 受影响行数
     */
    int updateContent(MailTemplateOverride template);

    /**
     * 逻辑删除覆盖记录,使该模板回落到内置版本。
     *
     * @param realmId      身份域主键
     * @param templateCode 模板编码
     * @return 受影响行数;未覆盖时为 0
     */
    int deleteByRealmAndCode(@Param("realmId") Long realmId,
                             @Param("templateCode") String templateCode);
}
