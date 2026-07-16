package club.beenest.payment.shared.constant;

import java.util.Map;

/**
 * 业务类型常量
 *
 * <p>用于多租户数据隔离。biz_type 标识业务类型，app_id 标识业务系统。</p>
 *
 * <p>多租户隔离维度：
 * <ul>
 *   <li>app_id — 业务系统标识（DRONE/SHOP），BFF 层按此过滤，确保业务系统只能查自己的数据</li>
 *   <li>biz_type — 业务类型标识（DRONE_ORDER/CHANNEL_ORDER/ALLIANCE_MEMBERSHIP/SHOP_ORDER），由前端透传用于业务筛选</li>
 * </ul>
 * </p>
 */
public final class BizTypeConstants {

    private BizTypeConstants() {
    }

    // ==================== 业务系统标识（app_id） ====================

    /** 无人机系统 */
    public static final String APP_ID_DRONE = "DRONE";

    /** 商城系统 */
    public static final String APP_ID_SHOP = "SHOP";

    // ==================== 业务类型标识（biz_type） ====================

    /** 无人机订单 */
    public static final String DRONE_ORDER = "DRONE_ORDER";

    /** 渠道订单 */
    public static final String CHANNEL_ORDER = "CHANNEL_ORDER";

    /** 联盟加盟 */
    public static final String ALLIANCE_MEMBERSHIP = "ALLIANCE_MEMBERSHIP";

    /** 商城订单 */
    public static final String SHOP_ORDER = "SHOP_ORDER";

    /** 默认业务类型（钱包/提现等无明确业务上下文时使用） */
    public static final String DEFAULT = DRONE_ORDER;

    // ==================== biz_type → app_id 映射 ====================

    /**
     * biz_type 到 app_id 的映射表。
     * 新增业务类型时必须在此注册，否则推导失败。
     */
    private static final Map<String, String> BIZ_TYPE_TO_APP_ID = Map.of(
            DRONE_ORDER, APP_ID_DRONE,
            CHANNEL_ORDER, APP_ID_DRONE,
            ALLIANCE_MEMBERSHIP, APP_ID_DRONE,
            SHOP_ORDER, APP_ID_SHOP
    );

    /**
     * 根据 bizType 推导对应的 appId
     *
     * @param bizType 业务类型
     * @return 对应的业务系统标识，未匹配时返回 APP_ID_DRONE（安全兜底）
     */
    public static String deriveAppId(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            return APP_ID_DRONE;
        }
        return BIZ_TYPE_TO_APP_ID.getOrDefault(bizType, APP_ID_DRONE);
    }
}
