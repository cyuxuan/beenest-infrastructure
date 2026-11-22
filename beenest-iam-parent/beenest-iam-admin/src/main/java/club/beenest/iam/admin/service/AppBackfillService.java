package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AppBackfillImportRequest;
import club.beenest.iam.admin.dto.AppBackfillImportResponse;
import club.beenest.iam.admin.dto.AppBackfillReportResponse;

/**
 * 应用权限回填服务(存量应用切换 SCOPED 令牌档的前置工具)。
 */
public interface AppBackfillService {

    /**
     * 生成回填报告,列出该应用切到 SCOPED 后会缺失的权限与菜单。
     *
     * @param appId 应用服务主键
     * @return 回填报告,含候选资源与建议策略
     * @throws BusinessException 应用不存在或不属于当前域
     */
    AppBackfillReportResponse report(Long appId);

    /**
     * 执行回填,把选中的平台级资源迁移(MOVE)或复制(COPY)到应用名下。
     * <p>整个导入在单个事务内完成。</p>
     *
     * @param appId   应用服务主键
     * @param request 回填请求,含策略与选中的权限、菜单主键
     * @return 执行结果,含迁移数、复制数与冲突项
     * @throws BusinessException 应用不存在或不属于当前域,或选中资源缺失、跨域
     */
    AppBackfillImportResponse importBackfill(Long appId, AppBackfillImportRequest request);
}
