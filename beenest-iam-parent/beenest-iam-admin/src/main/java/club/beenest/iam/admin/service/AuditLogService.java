package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AuditLogResponse;
import club.beenest.iam.common.result.PageResult;

import java.time.LocalDateTime;

/**
 * 审计日志查询服务,提供只读分页查询与 CSV 导出。
 * <p>查询与导出均按当前身份域隔离。</p>
 */
public interface AuditLogService {

    /**
     * 分页查询当前域审计日志。
     *
     * @param action       动作编码精确过滤,可空
     * @param operatorName 操作人登录名模糊过滤,可空
     * @param targetType   目标类型精确过滤,如 USER/ROLE/CLIENT,可空
     * @param fromTime     起始时间,可空
     * @param toTime       结束时间,可空
     * @param pageNum      页码,从 1 开始
     * @param pageSize     每页条数
     * @return 审计日志分页结果
     */
    PageResult<AuditLogResponse> page(String action, String operatorName, String targetType,
                                      LocalDateTime fromTime, LocalDateTime toTime,
                                      int pageNum, int pageSize);

    /**
     * 按条件导出当前域审计日志为 CSV 文本。
     * <p>与分页查询共用同一套过滤条件,导出结果与查询命中同一批数据。</p>
     *
     * @param action       动作编码精确过滤,可空
     * @param operatorName 操作人登录名模糊过滤,可空
     * @param targetType   目标类型精确过滤,可空
     * @param fromTime     起始时间,可空
     * @param toTime       结束时间,可空
     * @param maxRows      单次导出行数上限
     * @return CSV 文本,首行为表头
     */
    String exportCsv(String action, String operatorName, String targetType,
                     LocalDateTime fromTime, LocalDateTime toTime, int maxRows);
}
