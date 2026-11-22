package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 应用权限回填报告。
 * <p>列出尚未归属该应用的权限与菜单候选,并给出建议的回填策略。</p>
 *
 * @param appId              应用主键
 * @param appCode            应用编码
 * @param appName            应用名称
 * @param tokenProfile       当前令牌档
 * @param permissions        待回填的权限候选
 * @param menus              待回填的菜单候选
 * @param movableCount       可直接归属迁移的候选数(平台级且无冲突)
 * @param copyOnlyCount      只能复制到应用的候选数(已归属其它应用)
 * @param sharedCount        被多个应用共用的候选数
 */
public record AppBackfillReportResponse(
        Long appId,
        String appCode,
        String appName,
        String tokenProfile,
        List<BackfillCandidate> permissions,
        List<BackfillCandidate> menus,
        int movableCount,
        int copyOnlyCount,
        int sharedCount
) {

    /**
     * 回填候选。
     *
     * @param kind           资源类型(PERMISSION / MENU)
     * @param id             资源主键
     * @param code           资源编码
     * @param name           资源名称
     * @param platform       当前是否平台级(未归属任何应用)
     * @param ownerAppCode   当前归属应用编码(平台级为空)
     * @param strategy       建议策略(MOVE 归属迁移 / COPY 复制到应用)
     * @param reasons        来源说明
     * @param roleCodes      引用该资源的应用侧角色
     * @param realmRoleCodes 引用该资源的域角色
     * @param sharedAppCodes 同样在用该资源的其它应用
     */
    public record BackfillCandidate(
            String kind,
            Long id,
            String code,
            String name,
            boolean platform,
            String ownerAppCode,
            String strategy,
            List<String> reasons,
            List<String> roleCodes,
            List<String> realmRoleCodes,
            List<String> sharedAppCodes
    ) {
    }
}
