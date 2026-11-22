package club.beenest.iam.security;

import club.beenest.iam.authz.security.PasswordPolicyService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口令策略服务单元测试。
 */
class PasswordPolicyServiceTest {

    private final PasswordPolicyService service =
            new PasswordPolicyService(null, null);

    private PasswordPolicy policy(boolean upper, boolean lower, boolean digit, boolean symbol,
                                  int min, int max, int history, int expire) {
        PasswordPolicy p = new PasswordPolicy();
        p.setRequireUppercase(upper);
        p.setRequireLowercase(lower);
        p.setRequireDigit(digit);
        p.setRequireSymbol(symbol);
        p.setMinLength(min);
        p.setMaxLength(max);
        p.setHistoryCount(history);
        p.setExpireDays(expire);
        return p;
    }

    @Test
    @DisplayName("保守基线:8 位起步缺省要求数字")
    void defaultBaseline() {
        PasswordPolicy fallback = PasswordPolicyService.FALLBACK_POLICY;
        assertEquals(8, fallback.getMinLength());
        assertTrue(fallback.getRequireDigit());
    }

    @Test
    @DisplayName("复杂度校验逐项生效")
    void complexityRules() {
        PasswordPolicy p = policy(true, true, true, true, 8, 16, 0, 0);
        // 全部满足
        service.validate("Abcdefg1!", p);
        // 缺大写
        BusinessException e1 = assertThrows(BusinessException.class, () -> service.validate("abcdefg1!", p));
        assertTrue(e1.getMessage().contains("大写"));
        // 缺数字
        BusinessException e2 = assertThrows(BusinessException.class, () -> service.validate("Abcdefghi!", p));
        assertTrue(e2.getMessage().contains("数字"));
        // 缺符号
        BusinessException e3 = assertThrows(BusinessException.class, () -> service.validate("Abcdefg12", p));
        assertTrue(e3.getMessage().contains("符号"));
        // 长度不足
        BusinessException e4 = assertThrows(BusinessException.class, () -> service.validate("Ab1!", p));
        assertTrue(e4.getMessage().contains("长度不足"));
        // 超过最大长度
        BusinessException e5 = assertThrows(BusinessException.class,
                () -> service.validate("Abcdefg1!Abcdefg1!Abcdefg1!", p));
        assertTrue(e5.getMessage().contains("长度超出"));
    }

    @Test
    @DisplayName("空口令与 null 策略兜底")
    void edgeCases() {
        assertThrows(BusinessException.class, () -> service.validate("", policy(false, false, false, false, 8, 64, 0, 0)));
        assertThrows(BusinessException.class, () -> service.validate(null, null));
        // null 策略走保守基线:8 位 + 数字
        service.validate("12345678", null);
        assertThrows(BusinessException.class, () -> service.validate("abcdefgh", null));
    }
}
