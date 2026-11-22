package club.beenest.iam.persistence.entity;

import lombok.Data;

/**
 * 组成员数聚合结果(非表实体),由一条 GROUP BY 查询返回各组计数。
 */
@Data
public class GroupMemberCount {

    /** 组主键 */
    private Long groupId;
    /** 该组的成员数 */
    private Long memberCount;
}
