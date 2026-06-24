package club.beenest.payment.payscore;

import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.shared.constant.PaymentConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 信用免押检查结果DTO测试
 *
 * @author System
 * @since 2026-06-15
 */
class CreditCheckResultDTOTest {

    @Test
    @DisplayName("完全免押结果构建正确")
    void fullExemptResult() {
        CreditCheckResultDTO result = new CreditCheckResultDTO();
        result.setCustomerNo("U123");
        result.setPlatform(PaymentConstants.PLATFORM_WECHAT_PAYSCORE);
        result.setEligible(true);
        result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_FULL);
        result.setCreditScore(750);
        result.setDepositAmount(100000L);
        result.setFrozenAmount(0L);
        result.setMessage("芝麻分750，满足免押条件");

        assertTrue(result.isEligible());
        assertEquals(PaymentConstants.CREDIT_EXEMPTION_FULL, result.getExemptionResult());
        assertEquals(0L, result.getFrozenAmount());
    }

    @Test
    @DisplayName("不满足免押结果构建正确")
    void notExemptResult() {
        CreditCheckResultDTO result = new CreditCheckResultDTO();
        result.setEligible(false);
        result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_NONE);
        result.setDepositAmount(100000L);
        result.setFrozenAmount(100000L);
        result.setMessage("芝麻分不足，不满足免押条件");

        assertFalse(result.isEligible());
        assertEquals(100000L, result.getFrozenAmount());
    }

    @Test
    @DisplayName("链式设置器工作正常")
    void chainSetters() {
        CreditCheckResultDTO result = new CreditCheckResultDTO()
                .setCustomerNo("U123")
                .setPlatform("WECHAT_PAYSCORE")
                .setEligible(true)
                .setExemptionResult("FULL_EXEMPT")
                .setDepositAmount(100000L)
                .setFrozenAmount(0L)
                .setMessage("test");

        assertEquals("U123", result.getCustomerNo());
        assertEquals("WECHAT_PAYSCORE", result.getPlatform());
    }
}
