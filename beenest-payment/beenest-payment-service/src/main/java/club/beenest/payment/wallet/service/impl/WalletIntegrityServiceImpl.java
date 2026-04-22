package club.beenest.payment.wallet.service.impl;

import club.beenest.payment.wallet.mapper.WalletIntegrityLogMapper;
import club.beenest.payment.wallet.mapper.WalletMapper;
import club.beenest.payment.wallet.mapper.WalletTransactionMapper;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.domain.entity.WalletIntegrityLog;
import club.beenest.payment.wallet.domain.enums.WalletStatus;
import club.beenest.payment.wallet.security.BalanceHashCalculator;
import club.beenest.payment.wallet.service.IWalletIntegrityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钱包完整性校验服务实现
 *
 * <p>校验逻辑：</p>
 * <ul>
 *   <li>expectedBalance = SUM(amount) from transactions where status='SUCCESS'</li>
 *   <li>actualBalance = wallet.balance + wallet.frozen_balance</li>
 *   <li>如果 expectedBalance != actualBalance，说明余额被篡改</li>
 *   <li>同时校验 balance_hash 是否匹配</li>
 * </ul>
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
@Service
public class WalletIntegrityServiceImpl implements IWalletIntegrityService {

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private WalletIntegrityLogMapper integrityLogMapper;

    @Override
    public Map<String, Object> checkSingleWallet(String walletNo) {
        Wallet wallet = walletMapper.selectByWalletNo(walletNo);
        if (wallet == null) {
            throw new IllegalArgumentException("钱包不存在: " + walletNo);
        }

        // 1. 计算流水期望余额
        Long transactionSum = walletTransactionMapper.selectSumAmountByWalletNo(walletNo);
        long expectedTotal = transactionSum != null ? transactionSum : 0L;
        long actualTotal = (wallet.getBalance() != null ? wallet.getBalance() : 0L)
                + (wallet.getFrozenBalance() != null ? wallet.getFrozenBalance() : 0L);

        boolean balanceConsistent = (expectedTotal == actualTotal);

        // 2. 校验哈希
        boolean hashValid = BalanceHashCalculator.verify(wallet);

        boolean overallConsistent = balanceConsistent && hashValid;

        Map<String, Object> result = new HashMap<>();
        result.put("walletNo", walletNo);
        result.put("customerNo", wallet.getCustomerNo());
        result.put("expectedBalance", expectedTotal);
        result.put("actualBalance", actualTotal);
        result.put("balanceConsistent", balanceConsistent);
        result.put("hashValid", hashValid);
        result.put("consistent", overallConsistent);

        if (!overallConsistent) {
            String detail = buildDetail(balanceConsistent, hashValid, expectedTotal, actualTotal);
            logIntegrityIssue(wallet, expectedTotal, actualTotal, hashValid, detail);
            log.error("【安全告警】钱包完整性校验失败 - walletNo={}, {}", walletNo, detail);
        }

        return result;
    }

    @Override
    public Map<String, Object> checkAllWallets() {
        log.info("开始批量钱包完整性校验");

        // 查询所有钱包（不分页，全量扫描）
        List<Wallet> wallets = walletMapper.selectAllWithConditions(null, null, null, null);

        int total = wallets.size();
        int passCount = 0;
        int failCount = 0;

        for (Wallet wallet : wallets) {
            try {
                Map<String, Object> checkResult = checkSingleWallet(wallet.getWalletNo());
                boolean consistent = (boolean) checkResult.get("consistent");
                if (consistent) {
                    passCount++;
                } else {
                    failCount++;
                    // 冻结异常钱包
                    freezeWalletIfAnomalous(wallet);
                }
            } catch (Exception e) {
                failCount++;
                log.error("校验钱包异常 - walletNo={}, error={}", wallet.getWalletNo(), e.getMessage(), e);
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("passCount", passCount);
        summary.put("failCount", failCount);

        log.info("批量钱包完整性校验完成 - total={}, pass={}, fail={}", total, passCount, failCount);
        return summary;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcile(String walletNo) {
        log.info("开始修复钱包余额 - walletNo={}", walletNo);

        Wallet wallet = walletMapper.selectByWalletNo(walletNo);
        if (wallet == null) {
            throw new IllegalArgumentException("钱包不存在: " + walletNo);
        }

        Long transactionSum = walletTransactionMapper.selectSumAmountByWalletNo(walletNo);
        long expectedTotal = transactionSum != null ? transactionSum : 0L;
        long actualTotal = (wallet.getBalance() != null ? wallet.getBalance() : 0L)
                + (wallet.getFrozenBalance() != null ? wallet.getFrozenBalance() : 0L);

        if (expectedTotal == actualTotal) {
            log.info("钱包余额一致，无需修复 - walletNo={}", walletNo);
            return;
        }

        // 计算差额并修正 balance（保持 frozenBalance 不变）
        long diff = expectedTotal - actualTotal;
        long newBalance = (wallet.getBalance() != null ? wallet.getBalance() : 0L) + diff;

        String newHash = BalanceHashCalculator.calculate(
                newBalance,
                wallet.getFrozenBalance(),
                wallet.getVersion() + 1,
                wallet.getWalletNo());

        // 直接更新 balance 和 hash（管理员修复操作）
        int updated = walletMapper.reconcileBalance(wallet.getWalletNo(), newBalance, newHash, wallet.getVersion());
        if (updated <= 0) {
            throw new RuntimeException("修复钱包余额失败（乐观锁冲突）- walletNo=" + walletNo);
        }

        log.info("修复钱包余额完成 - walletNo={}, diff={}, newBalance={}", walletNo, diff, newBalance);
    }

    private String buildDetail(boolean balanceConsistent, boolean hashValid,
                               long expectedTotal, long actualTotal) {
        StringBuilder sb = new StringBuilder();
        if (!balanceConsistent) {
            sb.append("余额不一致(expected=").append(expectedTotal)
              .append(", actual=").append(actualTotal).append(")");
        }
        if (!hashValid) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("余额哈希校验失败");
        }
        return sb.toString();
    }

    private void logIntegrityIssue(Wallet wallet, long expectedTotal, long actualTotal,
                                   boolean hashValid, String detail) {
        WalletIntegrityLog integrityLog = new WalletIntegrityLog();
        integrityLog.setWalletNo(wallet.getWalletNo());
        integrityLog.setCustomerNo(wallet.getCustomerNo());
        integrityLog.setExpectedBalance(expectedTotal);
        integrityLog.setActualBalance(actualTotal);
        integrityLog.setExpectedFrozeBalance(0L);
        integrityLog.setActualFrozeBalance(wallet.getFrozenBalance() != null ? wallet.getFrozenBalance() : 0L);
        integrityLog.setHashValid(hashValid);
        integrityLog.setStatus("DETECTED");
        integrityLog.setDetail(detail);
        integrityLog.setCreateTime(LocalDateTime.now());

        try {
            integrityLogMapper.insert(integrityLog);
        } catch (Exception e) {
            log.error("记录完整性日志失败 - walletNo={}", wallet.getWalletNo(), e);
        }
    }

    private void freezeWalletIfAnomalous(Wallet wallet) {
        try {
            walletMapper.updateStatus(wallet.getWalletNo(), WalletStatus.FROZEN.getCode(), wallet.getVersion());
            log.warn("已冻结异常钱包 - walletNo={}, customerNo={}", wallet.getWalletNo(), wallet.getCustomerNo());
        } catch (Exception e) {
            log.error("冻结异常钱包失败 - walletNo={}", wallet.getWalletNo(), e);
        }
    }
}
