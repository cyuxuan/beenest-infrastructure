package club.beenest.payment.admin.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.paymentorder.dto.PaymentEventQueryDTO;
import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentEvent;
import club.beenest.payment.reconciliation.domain.entity.ReconciliationTask;
import club.beenest.payment.paymentorder.service.IPaymentEventService;
import club.beenest.payment.reconciliation.service.IReconciliationService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 对账管理后台控制器
 * 对账任务管理、支付事件日志查询与重试
 */
@Tag(name = "对账与运维管理", description = "对账、事件日志等API")
@RestController
@RequestMapping("/api/admin/payment")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Slf4j
public class ReconciliationAdminController {

    @Autowired
    private IReconciliationService reconciliationService;

    @Autowired
    private IPaymentEventService paymentEventService;

    @Operation(summary = "查询对账任务", description = "分页查询对账任务")
    @PostMapping("/reconciliation/page")
    @LogAudit(module = "支付运维", operation = "查询对账任务")
    public Response<PageInfo<ReconciliationTask>> queryReconciliationTasks(@Valid @RequestBody ReconciliationQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<ReconciliationTask> page = reconciliationService.queryTasks(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "创建对账任务", description = "手动创建对账任务")
    @PostMapping("/reconciliation/create")
    @LogAudit(module = "支付运维", operation = "创建对账任务")
    public Response<Void> createReconciliationTask(@RequestBody Map<String, String> params) {
        String date = params.get("date");
        String channel = params.get("channel");
        reconciliationService.createTask(date, channel);
        return Response.success();
    }

    @Operation(summary = "查询支付事件", description = "分页查询支付事件日志")
    @PostMapping("/events/page")
    @LogAudit(module = "支付运维", operation = "查询支付事件")
    public Response<PageInfo<PaymentEvent>> queryPaymentEvents(@Valid @RequestBody PaymentEventQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<PaymentEvent> page = paymentEventService.queryEvents(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "重试支付事件", description = "重试失败的支付事件")
    @PostMapping("/events/{id}/replay")
    @LogAudit(module = "支付运维", operation = "重试支付事件")
    public Response<Void> replayPaymentEvent(@PathVariable Long id) {
        paymentEventService.replayEvent(id);
        return Response.success();
    }
}
