package club.beenest.payment.common.utils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交易编号生成器
 */
public class TradeNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);
    private static final int MAX_SEQUENCE = 999;

    private TradeNoGenerator() {
    }

    public static String generatePaymentOrderNo() {
        return generateOrderNo("P");
    }

    public static String generateWithdrawOrderNo() {
        return generateOrderNo("W");
    }

    public static String generateWithdrawRequestNo() {
        return generateOrderNo("WR");
    }

    public static String generateTransactionNo() {
        return generateOrderNo("T");
    }

    public static String generateRefundNo() {
        return generateOrderNo("R");
    }

    public static String generate(String prefix) {
        return generateOrderNo(prefix);
    }

    private static synchronized String generateOrderNo(String prefix) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        int randomNum = RANDOM.nextInt(1000000);
        String randomStr = String.format("%06d", randomNum);
        int sequence = SEQUENCE.getAndIncrement();
        if (sequence > MAX_SEQUENCE) {
            SEQUENCE.set(0);
            sequence = 0;
        }
        String sequenceStr = String.format("%03d", sequence);
        return prefix + timestamp + randomStr + sequenceStr;
    }
}
