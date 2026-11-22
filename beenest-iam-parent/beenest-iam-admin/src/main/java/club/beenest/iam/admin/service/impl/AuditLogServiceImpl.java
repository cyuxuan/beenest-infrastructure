package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AuditLogResponse;
import club.beenest.iam.admin.service.AuditLogService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.AuditLog;
import club.beenest.iam.persistence.mapper.AuditLogMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计日志查询实现。
 * <p>管理端只提供只读查询与 CSV 导出,按身份域隔离;导出复用列表查询并以分页承载行数上限。</p>
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    /** 导出时间格式 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** CSV 单元格起始的公式防护字符:以 = + - @ 开头的值在 Excel 里会被当公式执行 */
    private static final char[] FORMULA_PREFIXES = {'=', '+', '-', '@'};

    private final AuditLogMapper auditLogMapper;

    public AuditLogServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public PageResult<AuditLogResponse> page(String action, String operatorName, String targetType,
                                             LocalDateTime fromTime, LocalDateTime toTime,
                                             int pageNum, int pageSize) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        PageHelper.startPage(pageNum, pageSize);
        List<AuditLog> list = auditLogMapper.selectByCondition(
                realmId, action, operatorName, targetType, fromTime, toTime);
        PageInfo<AuditLog> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(),
                pageInfo.getList().stream().map(this::toResponse).toList());
    }

    @Override
    public String exportCsv(String action, String operatorName, String targetType,
                            LocalDateTime fromTime, LocalDateTime toTime, int maxRows) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 用分页承载行数上限:导出走的是同一条查询,分页助手会顺带截断
        PageHelper.startPage(1, maxRows);
        List<AuditLog> list = auditLogMapper.selectByCondition(
                realmId, action, operatorName, targetType, fromTime, toTime);
        StringBuilder csv = new StringBuilder();
        csv.append("发生时间,操作人,动作,描述,目标类型,目标主键,来源IP,操作人主键\n");
        for (AuditLog log : list) {
            csv.append(csvCell(log.getOccurredTime() == null ? "" : log.getOccurredTime().format(TIME_FORMAT)))
                    .append(',').append(csvCell(log.getOperatorName()))
                    .append(',').append(csvCell(log.getAction()))
                    .append(',').append(csvCell(log.getDescription()))
                    .append(',').append(csvCell(log.getTargetType()))
                    .append(',').append(csvCell(log.getTargetId()))
                    .append(',').append(csvCell(log.getIp()))
                    .append(',').append(csvCell(log.getOperatorId()))
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * CSV 单元格转义,含逗号、引号或换行的值用双引号包裹。
     * <p>以 {@code = + - @} 开头的值前置一个单引号,防止被表格软件当作公式执行。</p>
     *
     * @param value 原始值
     * @return 转义后的单元格文本
     */
    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty() && containsFormulaPrefix(text)) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /**
     * 判断值是否以公式控制字符开头。
     *
     * @param text 待判定文本
     * @return 以 = + - @ 开头时为 true
     */
    private static boolean containsFormulaPrefix(String text) {
        char first = text.charAt(0);
        for (char prefix : FORMULA_PREFIXES) {
            if (first == prefix) {
                return true;
            }
        }
        return false;
    }

    /**
     * 审计日志转响应。
     *
     * @param log 审计日志
     * @return 审计日志响应
     */
    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getOperatorId(), log.getOperatorName(),
                log.getAction(), log.getDescription(), log.getTargetType(), log.getTargetId(),
                log.getIp(), log.getOccurredTime());
    }
}
