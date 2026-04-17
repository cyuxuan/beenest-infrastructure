package club.beenest.payment.service;

import java.util.Map;

/**
 * 钱包完整性校验服务
 * 校验钱包余额是否与交易流水一致，检测数据篡改
 *
 * @author System
 * @since 2026-04-01
 */
public interface IWalletIntegrityService {

    /**
     * 校验单个钱包
     *
     * @param walletNo 钱包编号
     * @return 校验结果，包含 expectedBalance, actualBalance, hashValid, consistent 等字段
     */
    Map<String, Object> checkSingleWallet(String walletNo);

    /**
     * 批量校验所有钱包
     *
     * @return 校验结果摘要：total, passCount, failCount
     */
    Map<String, Object> checkAllWallets();

    /**
     * 以流水为准修复钱包余额（需 SUPER_ADMIN 权限）
     *
     * @param walletNo 钱包编号
     */
    void reconcile(String walletNo);
}
