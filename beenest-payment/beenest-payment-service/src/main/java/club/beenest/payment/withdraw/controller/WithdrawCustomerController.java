package club.beenest.payment.withdraw.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawResultDTO;
import club.beenest.payment.withdraw.service.IWithdrawService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提现 C 端控制器
 * 提供用户侧提现申请接口
 */
@Tag(name = "提现管理", description = "提现相关API接口")
@RestController
@RequestMapping("/api/wallet")
@Validated
@Slf4j
public class WithdrawCustomerController {

    @Autowired
    private IWithdrawService withdrawService;

    @Operation(summary = "创建提现申请", description = "创建用户提现申请")
    @PostMapping("/withdraw")
    @LogAudit
    public Response<WithdrawResultDTO> createWithdrawRequest(
            @Parameter(description = "提现请求参数") @Valid @RequestBody WithdrawRequestDTO withdrawRequest) {
        try {
            String customerNo = AuthUtils.requireCurrentUserId();
            if (!withdrawRequest.isValidAmount()) {
                return Response.fail(400, "提现金额不在有效范围内");
            }
            if (!withdrawRequest.isValidWithdrawType()) {
                return Response.fail(400, "不支持的提现类型");
            }
            if (!withdrawRequest.isValidAccountType()) {
                return Response.fail(400, "无效的账户类型");
            }
            if (!withdrawRequest.validateBankCardFields()) {
                return Response.fail(400, "银行卡提现时银行名称不能为空");
            }
            if (!withdrawRequest.isValidAccountNumber()) {
                return Response.fail(400, "账户号码格式不正确");
            }
            WithdrawResultDTO withdrawInfo = withdrawService.createWithdrawRequest(customerNo, withdrawRequest);
            return Response.success(withdrawInfo);
        } catch (IllegalArgumentException e) {
            log.warn("创建提现申请参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("创建提现申请失败：{}", e.getMessage(), e);
            return Response.fail(500, "创建提现申请失败，请稍后重试");
        }
    }
}
