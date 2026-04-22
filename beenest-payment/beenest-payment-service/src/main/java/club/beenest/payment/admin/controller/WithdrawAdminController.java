package club.beenest.payment.admin.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.common.utils.CsvExportUtils;
import club.beenest.payment.withdraw.dto.WithdrawAuditDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.withdraw.domain.entity.WithdrawRequest;
import club.beenest.payment.withdraw.service.IWithdrawService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 提现管理后台控制器
 * 管理端提现审核、查询和导出
 */
@Tag(name = "提现管理后台", description = "提现管理后台API接口")
@RestController
@RequestMapping("/api/admin/payment")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Slf4j
public class WithdrawAdminController {

    @Autowired
    private IWithdrawService withdrawService;

    @Operation(summary = "查询提现申请", description = "分页查询提现申请列表")
    @PostMapping("/withdraws/page")
    @LogAudit(module = "支付管理", operation = "查询提现申请")
    public Response<PageInfo<WithdrawRequest>> queryWithdrawRequests(@Valid @RequestBody WithdrawRequestQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<WithdrawRequest> page = withdrawService.queryRequests(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "审核提现申请", description = "审核提现申请（通过/拒绝）")
    @PostMapping("/withdraws/audit")
    @LogAudit(module = "支付管理", operation = "审核提现申请")
    public Response<Void> auditWithdrawRequest(@RequestBody @Valid WithdrawAuditDTO audit) {
        String currentUserId = AuthUtils.getCurrentUserId();
        if (currentUserId == null) {
            return Response.fail(401, "未获取到当前登录用户信息");
        }
        audit.setAuditBy(currentUserId);
        withdrawService.auditRequest(audit);
        return Response.success();
    }

    @Operation(summary = "导出提现", description = "导出提现数据")
    @PostMapping("/withdraws/export")
    @LogAudit(module = "支付管理", operation = "导出提现")
    public void exportWithdraws(@Valid @RequestBody WithdrawRequestQueryDTO query, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=withdraws.csv");
        response.getOutputStream().write(CsvExportUtils.bom());
        StringBuilder csv = new StringBuilder();
        csv.append("RequestNo,CustomerNo,Amount,Status,CreateTime\n");
        Page<WithdrawRequest> page = withdrawService.queryRequests(query, 1, 10000);
        for (WithdrawRequest req : page) {
            csv.append(CsvExportUtils.escapeValue(req.getRequestNo())).append(",")
                    .append(CsvExportUtils.escapeValue(req.getCustomerNo())).append(",")
                    .append(req.getAmount()).append(",")
                    .append(CsvExportUtils.escapeValue(req.getStatus())).append(",")
                    .append(CsvExportUtils.escapeValue(req.getCreateTime() != null ? req.getCreateTime().toString() : ""))
                    .append("\n");
        }
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
