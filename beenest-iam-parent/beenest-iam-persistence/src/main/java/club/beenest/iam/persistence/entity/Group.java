package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户组实体(iam_group,树形结构)。
 * <p>groupType 区分部门与普通用户组:部门是组织架构节点,组是任意集合;
 * 两者共用同一棵树与组角色授权,类型只影响展示与筛选,不参与权限继承。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Group extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 父组主键(为空表示根组) */
    private Long parentId;
    /** 组编码(域内唯一) */
    private String groupCode;
    /** 组名称 */
    private String groupName;
    /** 组类型:DEPARTMENT / GROUP */
    private String groupType;
    /** 部门负责人(用户主键),仅作展示与流程指派,不自带权限 */
    private Long leaderUserId;
    /** 排序号(升序) */
    private Integer sortOrder;
    /** 外部系统组织主键(HR/AD 同步锚点),不参与鉴权 */
    private String externalId;
}
