package club.beenest.payment.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额处理工具类
 */
public class MoneyUtil {

    private MoneyUtil() {
    }

    public static final int DEFAULT_SCALE = 2;
    public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;
    public static final BigDecimal CENTS_TO_YUAN_DIVISOR = new BigDecimal(100);
    public static final BigDecimal YUAN_TO_CENTS_MULTIPLIER = new BigDecimal(100);
    public static final BigDecimal ZERO_YUAN = BigDecimal.ZERO;
    public static final Long ZERO_CENTS = 0L;

    public static BigDecimal centsToYuan(Long cents) {
        if (cents == null) {
            return ZERO_YUAN;
        }
        return new BigDecimal(cents).divide(CENTS_TO_YUAN_DIVISOR, DEFAULT_SCALE, DEFAULT_ROUNDING_MODE);
    }

    public static Long yuanToCents(BigDecimal yuan) {
        if (yuan == null) {
            return ZERO_CENTS;
        }
        return yuan.multiply(YUAN_TO_CENTS_MULTIPLIER).setScale(0, DEFAULT_ROUNDING_MODE).longValue();
    }

    public static String formatMoney(Long cents) {
        BigDecimal yuan = centsToYuan(cents);
        return String.format("¥%,.2f", yuan);
    }

    public static String formatMoneyPlain(Long cents) {
        BigDecimal yuan = centsToYuan(cents);
        return String.format("%,.2f", yuan);
    }

    public static Long addCents(Long cents1, Long cents2) {
        Long amount1 = cents1 != null ? cents1 : ZERO_CENTS;
        Long amount2 = cents2 != null ? cents2 : ZERO_CENTS;
        return amount1 + amount2;
    }

    public static Long subtractCents(Long cents1, Long cents2) {
        Long amount1 = cents1 != null ? cents1 : ZERO_CENTS;
        Long amount2 = cents2 != null ? cents2 : ZERO_CENTS;
        return amount1 - amount2;
    }

    public static int compareCents(Long cents1, Long cents2) {
        Long amount1 = cents1 != null ? cents1 : ZERO_CENTS;
        Long amount2 = cents2 != null ? cents2 : ZERO_CENTS;
        return amount1.compareTo(amount2);
    }

    public static boolean isPositive(Long cents) {
        return cents != null && cents > 0;
    }

    public static boolean isZero(Long cents) {
        return cents == null || cents.equals(ZERO_CENTS);
    }

    public static boolean isNegative(Long cents) {
        return cents != null && cents < 0;
    }

    public static Long absCents(Long cents) {
        if (cents == null) {
            return ZERO_CENTS;
        }
        return Math.abs(cents);
    }

    public static Long calculatePercentage(Long cents, BigDecimal percentage) {
        if (cents == null || percentage == null) {
            return ZERO_CENTS;
        }
        BigDecimal amount = new BigDecimal(cents);
        BigDecimal result = amount.multiply(percentage).divide(new BigDecimal(100), 0, DEFAULT_ROUNDING_MODE);
        return result.longValue();
    }

    public static boolean isInRange(Long cents, Long minCents, Long maxCents) {
        if (cents == null) {
            return false;
        }
        if (minCents != null && cents < minCents) {
            return false;
        }
        return maxCents == null || cents <= maxCents;
    }
}
