package club.beenest.payment.payscore.mapper;

import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 服务订单 Mapper 接口
 * 对应数据库表：ds_service_order
 *
 * @author System
 * @since 2026-06-15
 */
@Mapper
public interface ServiceOrderMapper {

    /**
     * 插入服务订单
     *
     * @param order 服务订单实体
     * @return 影响行数
     */
    int insert(ServiceOrder order);

    /**
     * 根据订单号查询服务订单
     *
     * @param orderNo 订单号
     * @return 服务订单
     */
    ServiceOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 根据订单号查询服务订单（加行锁，防并发）
     *
     * @param orderNo 订单号
     * @return 服务订单
     */
    ServiceOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /**
     * 根据业务单号查询最新的服务订单
     *
     * @param bizNo 业务单号
     * @return 服务订单
     */
    ServiceOrder selectLatestByBizNo(@Param("bizNo") String bizNo);

    /**
     * 更新服务订单
     *
     * @param order 服务订单实体
     * @return 影响行数
     */
    int updateByOrderNo(ServiceOrder order);

    /**
     * 条件更新状态（CAS乐观锁，防止并发状态冲突）
     *
     * @param orderNo 订单号
     * @param expectedStatus 期望的当前状态
     * @param newStatus 新状态
     * @return 影响行数（0表示状态已变更，1表示更新成功）
     */
    int updateStatusIfCurrentStatus(@Param("orderNo") String orderNo,
                                    @Param("expectedStatus") String expectedStatus,
                                    @Param("newStatus") String newStatus);

    /**
     * 根据用户编号和状态查询服务订单列表
     *
     * @param customerNo 用户编号
     * @param status 状态
     * @return 服务订单列表
     */
    List<ServiceOrder> selectByCustomerNoAndStatus(@Param("customerNo") String customerNo,
                                                    @Param("status") String status);

    /**
     * 查询过期的待授权订单
     *
     * @param status 状态
     * @param limit 数量限制
     * @return 过期订单列表
     */
    List<ServiceOrder> selectExpiredOrders(@Param("status") String status,
                                            @Param("limit") int limit);
}
