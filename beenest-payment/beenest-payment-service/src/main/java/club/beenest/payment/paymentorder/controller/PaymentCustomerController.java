package club.beenest.payment.paymentorder.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentResultDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.service.IPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 客户端支付控制器
 * 提供用户下单支付相关的API接口
 *
 * @author System
 * @since 2026-03-04
 */
@Tag(name = "客户支付接口", description = "用户下单支付、查询支付状态等API")
@RestController
@RequestMapping("/api/customer/payment")
@Validated
@Slf4j
public class PaymentCustomerController {

    @Autowired
    private IPaymentService paymentService;

    @Operation(summary = "创建订单支付", description = "为计划订单创建支付，返回支付参数用于调起原生支付")
    @PostMapping("/create-order-payment")
    @LogAudit(module = "客户支付", operation = "创建订单支付")
    public Response<OrderPaymentResultDTO> createOrderPayment(@Valid @RequestBody OrderPaymentRequestDTO request) {
        String customerNo = AuthUtils.requireCurrentUserId();
        OrderPaymentResultDTO result = paymentService.createOrderPayment(customerNo, request);
        return Response.success(result);
    }

    @Operation(summary = "创建充值订单", description = "创建充值订单并返回支付参数")
    @PostMapping("/recharge")
    @LogAudit(module = "客户支付", operation = "创建充值订单")
    public Response<OrderPaymentResultDTO> createRechargeOrder(@Valid @RequestBody RechargeRequestDTO rechargeRequest) {
        String customerNo = AuthUtils.requireCurrentUserId();
        OrderPaymentResultDTO result = paymentService.createRechargeOrder(customerNo, rechargeRequest);
        return Response.success(result);
    }

    @Operation(summary = "查询支付状态", description = "查询订单的支付状态，用于前端轮询")
    @GetMapping("/query-status/{orderNo}")
    public Response<PaymentStatusDTO> queryPaymentStatus(@PathVariable String orderNo) {
        String customerNo = AuthUtils.requireCurrentUserId();
        PaymentStatusDTO result = paymentService.queryPaymentStatus(customerNo, orderNo);
        return Response.success(result);
    }

    @Operation(summary = "取消支付订单", description = "取消未支付的订单")
    @PostMapping("/cancel/{orderNo}")
    @LogAudit(module = "客户支付", operation = "取消支付订单")
    public Response<Boolean> cancelPaymentOrder(@PathVariable String orderNo) {
        String customerNo = AuthUtils.requireCurrentUserId();
        boolean result = paymentService.cancelRechargeOrder(customerNo, orderNo);
        return Response.success(result);
    }
}
