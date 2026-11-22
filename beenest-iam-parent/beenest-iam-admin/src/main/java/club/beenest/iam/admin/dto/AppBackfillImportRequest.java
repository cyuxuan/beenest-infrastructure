package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 应用权限回填导入请求。
 * <p>按主键显式选择要处理的资源;未选择的资源不做变更。</p>
 *
 * @param permissionIds 待处理的权限主键
 * @param menuIds       待处理的菜单主键
 * @param strategy      策略:MOVE 归属迁移(仅平台级资源) / COPY 复制到应用
 */
public record AppBackfillImportRequest(
        List<Long> permissionIds,
        List<Long> menuIds,
        @Pattern(regexp = "MOVE|COPY", message = "回填策略只能是 MOVE 或 COPY") String strategy
) {
}
