package club.beenest.payment.common.exception;

/**
 * 风控异常类
 */
public class RiskControlException extends BusinessException {

    public RiskControlException(String message) {
        super(403, message);
    }

    public RiskControlException(int code, String message) {
        super(code, message);
    }
}
