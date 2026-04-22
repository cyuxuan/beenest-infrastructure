package club.beenest.payment.admin.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.CsvExportUtils;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.wallet.dto.WalletAdminQueryDTO;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.service.IWalletService;
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
 * 钱包管理后台控制器
 * 管理端钱包查询、交易流水查询和导出
 */
@Tag(name = "钱包管理后台", description = "钱包管理后台API接口")
@RestController
@RequestMapping("/api/admin/payment")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Slf4j
public class WalletAdminController {

    @Autowired
    private IWalletService walletService;

    @Operation(summary = "查询钱包", description = "分页查询钱包列表")
    @PostMapping("/wallets/page")
    @LogAudit(module = "支付管理", operation = "查询钱包")
    public Response<PageInfo<Wallet>> queryWallets(@Valid @RequestBody WalletAdminQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<Wallet> page = walletService.queryWallets(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "查询交易记录", description = "分页查询交易流水")
    @PostMapping("/transactions/page")
    @LogAudit(module = "支付管理", operation = "查询交易记录")
    public Response<PageInfo<TransactionHistoryDTO>> queryTransactions(@Valid @RequestBody TransactionQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<TransactionHistoryDTO> page = walletService.queryTransactions(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "导出交易流水", description = "导出交易流水数据")
    @PostMapping("/transactions/export")
    @LogAudit(module = "支付管理", operation = "导出交易流水")
    public void exportTransactions(@Valid @RequestBody TransactionQueryDTO query, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=transactions.csv");
        response.getOutputStream().write(CsvExportUtils.bom());
        StringBuilder csv = new StringBuilder();
        csv.append("TransactionNo,Type,Amount,Status,CreateTime\n");
        Page<TransactionHistoryDTO> page = walletService.queryTransactions(query, 1, 10000);
        for (TransactionHistoryDTO t : page) {
            csv.append(CsvExportUtils.escapeValue(t.getTransactionNo())).append(",")
                    .append(CsvExportUtils.escapeValue(t.getTransactionTypeDisplayName())).append(",")
                    .append(t.getAmountInYuan()).append(",")
                    .append(CsvExportUtils.escapeValue(t.getStatusDisplayName())).append(",")
                    .append(CsvExportUtils.escapeValue(t.getFormattedCreateTime())).append("\n");
        }
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
