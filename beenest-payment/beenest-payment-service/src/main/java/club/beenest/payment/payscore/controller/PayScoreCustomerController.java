package club.beenest.payment.payscore.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;
import club.beenest.payment.payscore.service.IServiceOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 客户端支付分控制器
 * 提供用户信用免押授权相关的API接口
 *
 * @author System
 * @since 2026-06-15
 */
@Tag(name = "客户支付分接口", description = "用户信用免押授权、查询服务订单等API")
@RestController
@RequestMapping("/api/customer/payscore")
@Validated
@Slf4j
public class PayScoreCustomerController {

    @Autowired
    private IServiceOrderService serviceOrderService;

    @Operation(summary = "创建服务订单", description = "发起信用免押授权，返回跳转参数")
    @PostMapping("/create")
    @LogAudit(module = "信用免押", operation = "创建服务订单")
    public Response<ServiceOrderResultDTO> createServiceOrder(@Valid @RequestBody ServiceOrderCreateDTO request) {
        String customerNo = AuthUtils.requireCurrentUserId();
        ServiceOrderResultDTO result = serviceOrderService.createServiceOrder(customerNo, request);
        return Response.success(result);
    }

    @Operation(summary = "查询服务订单状态", description = "查询信用免押服务订单的状态")
    @GetMapping("/status/{orderNo}")
    public Response<ServiceOrderResultDTO> queryServiceOrderStatus(@PathVariable String orderNo) {
        String customerNo = AuthUtils.requireCurrentUserId();
        ServiceOrderResultDTO result = serviceOrderService.queryServiceOrderStatus(customerNo, orderNo);
        return Response.success(result);
    }

    @Operation(summary = "取消服务订单", description = "取消信用免押授权，解冻额度")
    @PostMapping("/cancel/{orderNo}")
    @LogAudit(module = "信用免押", operation = "取消服务订单")
    public Response<Boolean> cancelServiceOrder(@PathVariable String orderNo,
                                                 @RequestParam(required = false) String reason) {
        String customerNo = AuthUtils.requireCurrentUserId();
        // 校验订单归属
        ServiceOrderResultDTO orderResult = serviceOrderService.queryServiceOrderStatus(customerNo, orderNo);
        if (orderResult == null) {
            return Response.fail(404, "订单不存在");
        }
        boolean result = serviceOrderService.cancelServiceOrder(orderNo, reason);
        return Response.success(result);
    }
}
