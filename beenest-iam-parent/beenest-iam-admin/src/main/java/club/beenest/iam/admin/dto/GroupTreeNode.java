package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 用户组树节点。
 * <p>组按 parent_id 组织为层级树;树只表达归属,不参与授权继承,
 * 子组成员不继承父组角色。</p>
 *
 * @param id           组主键
 * @param parentId     父组主键(顶级组为 null)
 * @param groupCode    组编码
 * @param groupName    组名称
 * @param groupType    组织类型:DEPARTMENT(部门) / GROUP(普通组)
 * @param leaderUserId 部门负责人用户主键
 * @param leaderName   部门负责人名称
 * @param memberCount  成员数
 * @param depth        层级深度(顶级组为 0)
 * @param path         层级路径(如"研发中心 / 研发一组")
 * @param createdTime  创建时间
 * @param children     子组
 */
public record GroupTreeNode(
        Long id,
        Long parentId,
        String groupCode,
        String groupName,
        String groupType,
        Long leaderUserId,
        String leaderName,
        long memberCount,
        int depth,
        String path,
        java.time.LocalDateTime createdTime,
        List<GroupTreeNode> children
) {
}
