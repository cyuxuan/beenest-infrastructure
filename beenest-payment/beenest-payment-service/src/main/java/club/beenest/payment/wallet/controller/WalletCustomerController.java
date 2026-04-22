package club.beenest.payment.wallet.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.wallet.dto.WalletBalanceDTO;
import club.beenest.payment.wallet.service.IWalletService;
import com.github.pagehelper.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包 C 端控制器
 * 提供用户侧钱包余额查询和交易历史接口
 */
@Tag(name = "钱包管理", description = "钱包相关API接口")
@RestController
@RequestMapping("/api/wallet")
@Validated
@Slf4j
public class WalletCustomerController {

    @Autowired
    private IWalletService walletService;

    @Operation(summary = "查询钱包余额", description = "查询用户钱包的完整余额信息")
    @GetMapping("/balance")
    @LogAudit
    public Response<WalletBalanceDTO> getWalletBalance() {
        try {
            String customerNo = AuthUtils.requireCurrentUserId();
            WalletBalanceDTO balanceInfo = walletService.getWalletBalance(customerNo, null);
            return Response.success(balanceInfo);
        } catch (IllegalArgumentException e) {
            log.warn("查询钱包余额参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("查询钱包余额失败：{}", e.getMessage(), e);
            return Response.fail(500, "查询余额失败，请稍后重试");
        }
    }

    @Operation(summary = "查询交易历史", description = "分页查询用户的交易历史记录")
    @GetMapping("/transactions")
    @LogAudit
    public Response<Page<TransactionHistoryDTO>> getTransactionHistory(
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @Parameter(description = "每页大小，最大100") @RequestParam(defaultValue = "20") @Min(1) Integer pageSize,
            @Parameter(description = "交易类型过滤") @RequestParam(required = false) String transactionType) {
        try {
            if (pageSize > 100) {
                pageSize = 100;
            }
            String customerNo = AuthUtils.requireCurrentUserId();
            Page<TransactionHistoryDTO> transactionHistory = walletService.getTransactionHistory(
                    customerNo, null, pageNum, pageSize, transactionType);
            return Response.success(transactionHistory);
        } catch (IllegalArgumentException e) {
            log.warn("查询交易历史参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("查询交易历史失败：{}", e.getMessage(), e);
            return Response.fail(500, "查询交易历史失败，请稍后重试");
        }
    }
}
