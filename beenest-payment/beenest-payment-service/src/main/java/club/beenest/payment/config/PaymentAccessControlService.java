package club.beenest.payment.config;

import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.domain.enums.WalletStatus;
import club.beenest.payment.wallet.mapper.WalletMapper;
import club.beenest.payment.wallet.service.IWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.client.accesscontrol.CasUserAccessControlService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * 支付服务 CAS 用户访问控制 SPI 实现。
 * <p>
 * Payment 没有独立的用户表，用户身份通过钱包（ds_wallet）的 customer_no 字段关联 CAS userId。
 * 本实现将"用户存在且活跃"的语义定义为：该用户拥有状态为 ACTIVE 的钱包。
 * <p>
 * 触发时机：
 * <ul>
 *   <li>Bearer Token 验证/刷新时（API 模式，CasBearerTokenAuthenticationProvider）</li>
 * </ul>
 *
 * @see CasUserAccessControlService
 * @see Wallet
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAccessControlService implements CasUserAccessControlService {

    private final IWalletService walletService;
    private final WalletMapper walletMapper;

    @Override
    public String getRequiredRole() {
        return "ROLE_PAYMENT";
    }

    @Override
    public boolean isLocalUserActive(String userId) {
        // 检查用户是否有活跃钱包（customer_no = CAS userId）
        Wallet wallet = walletMapper.selectByCustomerNo(userId);
        return wallet != null && WalletStatus.ACTIVE.getCode().equals(wallet.getStatus());
    }

    @Override
    public String createLocalUser(String userId, Set<String> casRoles,
                                  Map<String, Object> casAttributes) {
        // 1. 为用户创建默认钱包（幂等：已存在则返回现有钱包）
        Wallet wallet = walletService.getOrCreateWallet(userId, "DRONE_ORDER");

        // 2. 从 CAS 属性同步用户姓名和手机号到钱包
        syncCustomerProfile(userId, casAttributes);

        log.info("CAS 访问控制: 自动创建支付用户 userId={}, walletNo={}", userId, wallet.getWalletNo());
        return userId;
    }

    @Override
    public void disableLocalUser(String userId, Set<String> casRoles) {
        // 冻结该用户的所有钱包
        int affected = walletMapper.freezeAllByCustomerNo(userId, WalletStatus.FROZEN.getCode());
        log.info("CAS 访问控制: 冻结用户钱包 userId={}, 冻结数量={}", userId, affected);
    }

    @Override
    public void updateLocalUser(String userId, Set<String> casRoles,
                                Map<String, Object> casAttributes) {
        // 从 CAS 属性同步用户姓名和手机号到钱包
        syncCustomerProfile(userId, casAttributes);
    }

    /**
     * 从 CAS 属性中提取用户姓名和手机号，同步到钱包。
     *
     * @param userId        CAS 用户 ID
     * @param casAttributes CAS 返回的属性（nickname, phone 等）
     */
    private void syncCustomerProfile(String userId, Map<String, Object> casAttributes) {
        if (casAttributes == null || casAttributes.isEmpty()) {
            return;
        }
        String customerName = getStringAttribute(casAttributes, "nickname");
        String customerPhone = getStringAttribute(casAttributes, "phone");

        if (StringUtils.hasText(customerName) || StringUtils.hasText(customerPhone)) {
            walletMapper.updateCustomerProfileByCustomerNo(userId, customerName, customerPhone);
        }
    }

    /**
     * 安全地从 CAS 属性 Map 中提取字符串值。
     *
     * @param attributes CAS 属性 Map
     * @param key        属性名
     * @return 字符串值，不存在时返回 null
     */
    private String getStringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
