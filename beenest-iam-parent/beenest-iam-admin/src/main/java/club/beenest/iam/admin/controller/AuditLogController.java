package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AuditLogResponse;
import club.beenest.iam.admin.service.AuditLogService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 审计日志查询接口(只读 + 导出)。
 * <p>查询与导出均按当前身份域隔离;导出走独立权限 {@code audit:export},
 * 单次导出行数受 {@code EXPORT_MAX_ROWS} 上限约束。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditLogController {

    /** 单次导出行数上限,超出部分不返回 */
    private static final int EXPORT_MAX_ROWS = 50_000;
    /** CSV 文件名里的时间戳格式 */
    private static final String FILE_STAMP = "yyyyMMddHHmmss";

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 分页查询审计日志。
     *
     * @param action       动作编码过滤(可选)
     * @param operatorName 操作人登录名模糊过滤(可选)
     * @param targetType   目标类型过滤(可选)
     * @param fromTime     起始时间(可选,ISO 格式)
     * @param toTime       结束时间(可选,ISO 格式)
     * @param pageNum      页码
     * @param pageSize     每页条数
     * @return 当前域审计日志分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public Result<PageResult<AuditLogResponse>> page(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(auditLogService.page(
                action, operatorName, targetType, fromTime, toTime, pageNum, pageSize));
    }

    /**
     * 导出审计日志(CSV)。
     * <p>用独立的 {@code audit:export} 权限:导出会把整批审计数据带出系统,
     * 读权限只覆盖控制台内翻页查看。</p>
     *
     * @param action       动作编码过滤(可选)
     * @param operatorName 操作人登录名模糊过滤(可选)
     * @param targetType   目标类型过滤(可选)
     * @param fromTime     起始时间(可选,ISO 格式)
     * @param toTime       结束时间(可选,ISO 格式)
     * @return CSV 文件响应,最多返回 {@code EXPORT_MAX_ROWS} 行,首行为表头
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('audit:export')")
    @LogAudit(action = "AUDIT_EXPORT", description = "导出审计日志")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String operatorName,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime) {
        String csv = auditLogService.exportCsv(action, operatorName, targetType, fromTime, toTime, EXPORT_MAX_ROWS);
        // UTF-8 BOM:Excel 按默认编码打开时保持中文列不乱码
        byte[] body = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
        String filename = "audit-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern(FILE_STAMP))
                + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }
}
