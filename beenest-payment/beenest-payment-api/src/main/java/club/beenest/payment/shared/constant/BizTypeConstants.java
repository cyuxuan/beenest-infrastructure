package club.beenest.payment.shared.constant;

import java.util.Map;

/**
 * 业务类型常量
 *
 * <p>用于多租户数据隔离。biz_type 标识业务类型，app_id 标识业务系统。</p>
 *
 * <p>多租户隔离维度：
 * <ul>
 *   <li>app_id — 业务系统标识（DRONE/SHOP），由 {@code X-App-Id} 请求头自动注入，
 *       服务端通过 {@code AppContext.getAppId()} 获取，客户端无需手动传入</li>
 *   <li>biz_type — 业务类型标识（DRONE_ORDER/CHANNEL_ORDER/ALLIANCE_MEMBERSHIP/SHOP_ORDER），
 *       客户端可选择查询属于自己的 bizType；查询基于 appId 过滤，不属于当前 appId 的 bizType
 *       查不到数据，不存在数据安全问题</li>
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
     * 用于 MQ 消息、定时任务等非请求上下文场景推导 appId。
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
     * <p>用于 MQ 消息、定时任务等非请求上下文场景，当实体已有 bizType 但缺少 appId 时推导。</p>
     *
     * @param bizType 业务类型
     * @return 对应的业务系统标识
     * @throws IllegalStateException bizType 为空或未注册时抛出，防止静默路由到错误租户
     */
    public static String deriveAppId(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            throw new IllegalStateException("bizType 为空，无法推导 appId，请检查调用方是否正确设置了 bizType");
        }
        String appId = BIZ_TYPE_TO_APP_ID.get(bizType);
        if (appId == null) {
            throw new IllegalStateException("bizType=" + bizType + " 未在 BizTypeConstants.BIZ_TYPE_TO_APP_ID 中注册，无法推导 appId");
        }
        return appId;
    }
}
