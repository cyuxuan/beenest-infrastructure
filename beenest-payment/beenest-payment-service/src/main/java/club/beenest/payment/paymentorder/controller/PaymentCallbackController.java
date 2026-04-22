package club.beenest.payment.paymentorder.controller;

import club.beenest.payment.paymentorder.service.IPaymentService;
import club.beenest.payment.paymentorder.strategy.PaymentStrategy;
import club.beenest.payment.paymentorder.strategy.PaymentStrategyFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付渠道回调控制器
 * 仅处理第三方支付/退款回调通知，不承载用户侧下单入口。
 */
@Tag(name = "支付管理", description = "支付相关API接口")
@RestController
@RequestMapping("/api/wallet")
@Validated
@Slf4j
public class PaymentCallbackController {

    @Autowired
    private IPaymentService paymentService;

    @Autowired
    private PaymentStrategyFactory paymentStrategyFactory;

    @Operation(summary = "支付回调处理", description = "处理第三方支付平台的回调通知")
    @PostMapping("/payment/callback/{platform}")
    public String handlePaymentCallback(
            @Parameter(description = "支付平台") @PathVariable @NotBlank String platform,
            HttpServletRequest request) {
        try {
            log.info("收到支付回调：平台={}", platform);
            boolean success = paymentService.handlePaymentCallback(platform, request);
            if (success) {
                return getSuccessCallbackResponse(platform);
            }
            return getFailureCallbackResponse(platform);
        } catch (Exception e) {
            log.error("处理支付回调失败：平台={}, 错误={}", platform, e.getMessage(), e);
            return getFailureCallbackResponse(platform);
        }
    }

    @Operation(summary = "退款回调处理", description = "处理第三方支付平台的退款回调通知")
    @PostMapping("/payment/refund/callback/{platform}")
    public String handleRefundCallback(
            @Parameter(description = "支付平台") @PathVariable @NotBlank String platform,
            HttpServletRequest request) {
        try {
            log.info("收到退款回调：平台={}", platform);
            boolean success = paymentService.handleRefundCallback(platform, request);
            if (success) {
                return getSuccessCallbackResponse(platform);
            }
            return getFailureCallbackResponse(platform);
        } catch (Exception e) {
            log.error("处理退款回调失败：平台={}, 错误={}", platform, e.getMessage(), e);
            return getFailureCallbackResponse(platform);
        }
    }

    private String getSuccessCallbackResponse(String platform) {
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(platform);
            return strategy.getSuccessResponse();
        } catch (Exception e) {
            log.warn("获取成功回调响应失败，使用默认兜底 - platform: {}, error: {}", platform, e.getMessage());
            return "SUCCESS";
        }
    }

    private String getFailureCallbackResponse(String platform) {
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(platform);
            return strategy.getFailureResponse();
        } catch (Exception e) {
            log.warn("获取失败回调响应失败，使用默认兜底 - platform: {}, error: {}", platform, e.getMessage());
            return "FAILURE";
        }
    }
}
