package club.beenest.payment.paymentorder.mapper;

import club.beenest.payment.paymentorder.domain.entity.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 退款数据访问接口
 */
@Mapper
public interface RefundMapper {

    /**
     * 新增退款记录
     * @param refund 退款实体
     * @return 影响行数
     */
    int insert(Refund refund);

    /**
     * 更新退款记录
     * @param refund 退款实体
     * @return 影响行数
     */
    int update(Refund refund);

    /**
     * 根据主键ID查询
     * @param id 主键ID
     * @return 退款实体
     */
    Refund selectById(Long id);

    /**
     * 根据退款单号查询
     * @param refundNo 退款单号
     * @return 退款实体
     */
    Refund selectByRefundNo(String refundNo);

    /**
     * 根据退款单号加行锁查询（退款回调并发安全）
     * 配合 SELECT ... FOR UPDATE 防止退款回调与主动同步并发冲突
     * @param refundNo 退款单号
     * @return 退款实体，带行锁
     */
    Refund selectByRefundNoForUpdate(@Param("refundNo") String refundNo);

    /**
     * 查询待补偿的退款单（PENDING + PROCESSING 状态）
     * PENDING 状态：可能因 down机 未提交到第三方，需要重新提交
     * PROCESSING 状态：已提交到第三方，需要查询结果
     * @param statuses 状态列表
     * @param limit 数量限制
     * @return 退款列表
     */
    List<Refund> selectByStatusesForSync(@Param("statuses") List<String> statuses, @Param("limit") int limit);

    /**
     * 根据第三方退款单号查询
     * @param thirdPartyRefundNo 第三方退款单号
     * @return 退款实体
     */
    Refund selectByThirdPartyRefundNo(@Param("thirdPartyRefundNo") String thirdPartyRefundNo);

    /**
     * 根据第三方退款单号加行锁查询（退款回调并发安全）
     * 配合 SELECT ... FOR UPDATE 防止退款回调与主动同步并发冲突
     * @param thirdPartyRefundNo 第三方退款单号
     * @return 退款实体，带行锁
     */
    Refund selectByThirdPartyRefundNoForUpdate(@Param("thirdPartyRefundNo") String thirdPartyRefundNo);

    /**
     * 根据订单号查询
     * @param orderNo 订单号
     * @return 退款列表
     */
    List<Refund> selectByOrderNo(String orderNo);

    /**
     * 查询待同步的处理中退款单
     * @param status 退款状态
     * @param limit 数量限制
     * @return 退款列表
     */
    List<Refund> selectByStatusForSync(@Param("status") String status, @Param("limit") int limit);

    /**
     * 查询退款列表
     * @param refundNo 退款单号
     * @param orderNo 订单号
     * @param status 状态
     * @return 退款列表
     */
    List<Refund> selectByQuery(@Param("refundNo") String refundNo,
                               @Param("orderNo") String orderNo,
                               @Param("status") String status,
                               @Param("refundPolicy") String refundPolicy,
                               @Param("requestSource") String requestSource,
                               @Param("applicantId") String applicantId,
                               @Param("channelStatus") String channelStatus);

    /**
     * 查询订单最新的待处理退款记录
     * @param orderNo 订单号
     * @return 退款记录
     */
    Refund selectLatestPendingByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 累计订单已成功退款金额
     * @param orderNo 订单号
     * @return 已成功退款总额（分），无记录时返回 0
     */
    Long sumSuccessAmountByOrderNo(@Param("orderNo") String orderNo);
}
