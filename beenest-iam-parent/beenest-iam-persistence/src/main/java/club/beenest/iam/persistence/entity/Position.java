package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 岗位实体(iam_position,域内平铺字典)。
 * <p>岗位为职务定义,经 iam_position_role 关联默认角色,任职该岗位即继承这些角色。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Position extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 岗位编码(域内唯一) */
    private String positionCode;
    /** 岗位名称 */
    private String positionName;
    /** 职级/序列(展示用文本,不参与鉴权) */
    private String positionLevel;
    /** 排序号(升序) */
    private Integer sortOrder;
}
