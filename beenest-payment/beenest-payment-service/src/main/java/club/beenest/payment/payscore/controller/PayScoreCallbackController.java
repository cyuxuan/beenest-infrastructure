package club.beenest.payment.payscore.controller;

import club.beenest.payment.payscore.service.IServiceOrderService;
import club.beenest.payment.payscore.strategy.PayScoreStrategy;
import club.beenest.payment.payscore.strategy.PayScoreStrategyFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 支付分回调控制器
 * 处理微信支付分/支付宝芝麻信用的异步回调通知
 *
 * <p>回调路径加入CAS ignore-pattern，不触发认证拦截。</p>
 *
 * @author System
 * @since 2026-06-15
 */
@Tag(name = "支付分回调", description = "支付分授权/完结/取消回调接口")
@RestController
@RequestMapping("/api/payscore/callback")
@Validated
@Slf4j
public class PayScoreCallbackController {

    @Autowired
    private IServiceOrderService serviceOrderService;

    @Autowired
    private PayScoreStrategyFactory payScoreStrategyFactory;

    @Operation(summary = "授权回调处理", description = "处理第三方支付分平台的授权确认回调")
    @PostMapping("/auth/{platform}")
    public String handleAuthCallback(
            @Parameter(description = "支付分平台") @PathVariable @NotBlank String platform,
            HttpServletRequest request) {
        try {
            log.info("收到支付分授权回调 - platform: {}", platform);
            boolean success = serviceOrderService.handleAuthCallback(platform, request);
            return getCallbackResponse(platform, success);
        } catch (Exception e) {
            log.error("处理支付分授权回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            return getCallbackResponse(platform, false);
        }
    }

    @Operation(summary = "完结回调处理", description = "处理第三方支付分平台的完结确认回调")
    @PostMapping("/complete/{platform}")
    public String handleCompleteCallback(
            @Parameter(description = "支付分平台") @PathVariable @NotBlank String platform,
            HttpServletRequest request) {
        try {
            log.info("收到支付分完结回调 - platform: {}", platform);
            boolean success = serviceOrderService.handleCompleteCallback(platform, request);
            return getCallbackResponse(platform, success);
        } catch (Exception e) {
            log.error("处理支付分完结回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            return getCallbackResponse(platform, false);
        }
    }

    /**
     * 获取回调响应字符串
     */
    private String getCallbackResponse(String platform, boolean success) {
        try {
            PayScoreStrategy strategy = payScoreStrategyFactory.getStrategy(platform);
            return success ? strategy.getSuccessResponse() : strategy.getFailureResponse();
        } catch (Exception e) {
            log.warn("获取回调响应失败 - platform: {}, error: {}", platform, e.getMessage());
            return success ? "SUCCESS" : "FAILURE";
        }
    }
}
