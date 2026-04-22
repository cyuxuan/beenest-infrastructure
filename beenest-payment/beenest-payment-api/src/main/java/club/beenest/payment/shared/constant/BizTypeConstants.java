package club.beenest.payment.shared.constant;

/**
 * 业务类型常量
 * 用于多租户钱包隔离，不同业务系统的用户钱包互不共享
 */
public final class BizTypeConstants {

    private BizTypeConstants() {
    }

    /** 无人机订单 */
    public static final String DRONE_ORDER = "DRONE_ORDER";

    /** 商城订单 */
    public static final String SHOP_ORDER = "SHOP_ORDER";

    /** 默认业务类型（钱包/提现等无明确业务上下文时使用） */
    public static final String DEFAULT = DRONE_ORDER;

}
