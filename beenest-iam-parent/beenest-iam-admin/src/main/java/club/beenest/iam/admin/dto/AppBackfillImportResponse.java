package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 应用权限回填执行结果。
 *
 * @param movedPermissions  归属迁移的权限数
 * @param movedMenus        归属迁移的菜单数
 * @param copiedPermissions 复制到应用的权限数
 * @param copiedMenus       复制到应用的菜单数
 * @param relinkedGrants    改指向或追加的授权引用数
 * @param conflicts         跳过的冲突说明
 */
public record AppBackfillImportResponse(
        int movedPermissions,
        int movedMenus,
        int copiedPermissions,
        int copiedMenus,
        int relinkedGrants,
        List<String> conflicts
) {
}
