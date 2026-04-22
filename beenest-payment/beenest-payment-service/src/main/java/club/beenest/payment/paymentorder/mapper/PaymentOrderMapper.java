package club.beenest.payment.paymentorder.mapper;

import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值订单数据访问接口
 * 提供充值订单相关的数据库操作方法
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>充值订单管理</li>
 *   <li>支付状态跟踪</li>
 *   <li>订单查询和统计</li>
 *   <li>支付回调处理</li>
 * </ul>
 * 
 * <h3>订单状态管理：</h3>
 * <ul>
 *   <li>PENDING - 待支付</li>
 *   <li>PAID - 已支付</li>
 *   <li>CANCELLED - 已取消</li>
 *   <li>EXPIRED - 已过期</li>
 *   <li>REFUNDED - 已退款</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Mapper
public interface PaymentOrderMapper {

    /**
     * 根据主键ID查询充值订单
     * 
     * <p>通过充值订单的主键ID查询完整的订单信息。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>订单详情查询</li>
     *   <li>数据关联查询</li>
     *   <li>管理后台操作</li>
     * </ul>
     * 
     * @param id 充值订单主键ID，不能为null
     * @return 充值订单实体对象，如果不存在则返回null
     */
    PaymentOrder selectById(@Param("id") Long id);

    /**
     * 根据订单号查询充值订单
     * 
     * <p>通过订单号查询完整的充值订单信息，这是最常用的查询方式。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>支付回调处理</li>
     *   <li>订单状态查询</li>
     *   <li>API接口调用</li>
     *   <li>问题排查和审计</li>
     * </ul>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>基于唯一索引，查询效率高</li>
     *   <li>返回完整的订单信息</li>
     *   <li>支持所有状态的订单</li>
     * </ul>
     * 
     * @param orderNo 订单号，不能为null或空字符串
     * @return 充值订单实体对象，如果不存在则返回null
     */
    PaymentOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 根据业务单号查询最近一条待支付订单（防重复下单）
     *
     * @param bizNo 业务单号
     * @return 最近待支付订单，不存在则返回null
     */
    PaymentOrder selectLatestPendingByBizNo(@Param("bizNo") String bizNo);

    /**
     * 根据业务单号查询最近一条支付订单
     *
     * @param bizNo 业务单号
     * @return 最近支付订单
     */
    PaymentOrder selectLatestByBizNo(@Param("bizNo") String bizNo);

    /**
     * 根据查询条件查询充值订单
     *
     * @param query 查询参数
     * @return 充值订单列表
     */
    List<PaymentOrder> selectByQuery(PaymentOrderQueryDTO query);

    /**
     * 根据第三方订单号查询充值订单
     * 
     * <p>通过第三方支付平台的订单号查询充值订单信息。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>支付回调处理</li>
     *   <li>订单状态同步</li>
     *   <li>支付平台对账</li>
     *   <li>问题排查</li>
     * </ul>
     * 
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>第三方订单号可能为空（创建时未生成）</li>
     *   <li>不同平台的订单号格式不同</li>
     *   <li>需要结合平台字段一起查询</li>
     * </ul>
     * 
     * @param thirdPartyOrderNo 第三方订单号，不能为null或空字符串
     * @param platform 支付平台，用于精确匹配，可以为null
     * @return 充值订单实体对象，如果不存在则返回null
     */
    PaymentOrder selectByThirdPartyOrderNo(@Param("thirdPartyOrderNo") String thirdPartyOrderNo,
                                           @Param("platform") String platform);

    /**
     * 插入新的充值订单记录
     * 
     * <p>创建新的充值订单记录，设置初始状态和过期时间。</p>
     * 
     * <h4>创建规则：</h4>
     * <ul>
     *   <li>订单号必须唯一</li>
     *   <li>初始状态为PENDING</li>
     *   <li>必须设置过期时间</li>
     *   <li>金额必须大于0</li>
     * </ul>
     * 
     * <h4>必填字段：</h4>
     * <ul>
     *   <li>orderNo - 订单号</li>
     *   <li>customerNo - 用户编号</li>
     *   <li>walletNo - 钱包编号</li>
     *   <li>amount - 充值金额</li>
     *   <li>platform - 支付平台</li>
     *   <li>paymentMethod - 支付方式</li>
     *   <li>expireTime - 过期时间</li>
     * </ul>
     * 
     * <h4>自动设置字段：</h4>
     * <ul>
     *   <li>status - PENDING</li>
     *   <li>createTime - 当前时间</li>
     *   <li>updateTime - 当前时间</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单实体对象，必须包含所有必填字段
     * @return 影响的行数，成功插入返回1
     */
    int insert(PaymentOrder paymentOrder);

    /**
     * 更新充值订单信息
     * 
     * <p>更新充值订单的基本信息，不包括状态字段。</p>
     * 
     * <h4>可更新字段：</h4>
     * <ul>
     *   <li>thirdPartyOrderNo - 第三方订单号</li>
     *   <li>thirdPartyTransactionNo - 第三方交易号</li>
     *   <li>paymentParams - 支付参数</li>
     *   <li>notifyUrl - 通知地址</li>
     *   <li>returnUrl - 跳转地址</li>
     *   <li>remark - 备注信息</li>
     * </ul>
     * 
     * <h4>不可更新字段：</h4>
     * <ul>
     *   <li>status - 订单状态（需要使用专门的方法）</li>
     *   <li>amount - 充值金额</li>
     *   <li>customerNo - 用户编号</li>
     *   <li>walletNo - 钱包编号</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单实体对象，必须包含orderNo
     * @return 影响的行数，成功更新返回1
     */
    int updateByOrderNo(PaymentOrder paymentOrder);

    /**
     * 更新订单状态
     * 
     * <p>更新充值订单的状态，用于订单状态流转。</p>
     * 
     * <h4>状态流转规则：</h4>
     * <ul>
     *   <li>PENDING → PAID（支付成功）</li>
     *   <li>PENDING → CANCELLED（用户取消）</li>
     *   <li>PENDING → EXPIRED（订单过期）</li>
     *   <li>PAID → REFUNDED（申请退款）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>支付回调处理</li>
     *   <li>订单取消操作</li>
     *   <li>订单过期处理</li>
     *   <li>退款处理</li>
     * </ul>
     * 
     * <h4>自动更新字段：</h4>
     * <ul>
     *   <li>updateTime - 当前时间</li>
     *   <li>paidTime - 支付完成时间（状态为PAID时）</li>
     * </ul>
     * 
     * @param orderNo 订单号，不能为null或空字符串
     * @param status 新的状态值，不能为null
     * @param callbackData 回调数据，JSON格式，可以为null
     * @param thirdPartyTransactionNo 第三方交易号，可以为null
     * @return 影响的行数，成功更新返回1
     */
    int updateStatus(@Param("orderNo") String orderNo,
                     @Param("status") String status,
                     @Param("callbackData") String callbackData,
                     @Param("thirdPartyTransactionNo") String thirdPartyTransactionNo);

    /**
     * 在当前状态匹配时更新订单状态，用于并发幂等控制。
     */
    int updateStatusIfCurrentStatus(@Param("orderNo") String orderNo,
                                    @Param("currentStatus") String currentStatus,
                                    @Param("status") String status,
                                    @Param("callbackData") String callbackData,
                                    @Param("thirdPartyTransactionNo") String thirdPartyTransactionNo);

    /**
     * 分页查询用户充值订单
     * 
     * <p>分页查询指定用户的充值订单记录，按时间倒序排列。</p>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>支持PageHelper分页</li>
     *   <li>按创建时间倒序排列</li>
     *   <li>支持状态过滤</li>
     *   <li>支持平台过滤</li>
     * </ul>
     * 
     * <h4>排序规则：</h4>
     * <ul>
     *   <li>主排序：创建时间倒序（最新的在前）</li>
     *   <li>次排序：ID倒序（确保顺序稳定）</li>
     * </ul>
     * 
     * <h4>使用方法：</h4>
     * <pre>
     * // 设置分页参数
     * PageHelper.startPage(pageNum, pageSize);
     * // 执行查询
     * Page&lt;PaymentOrder&gt; result = paymentOrderMapper.selectByCustomerNo(customerNo, null, null);
     * </pre>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @param status 订单状态过滤，null表示查询所有状态
     * @param platform 支付平台过滤，null表示查询所有平台
     * @return 分页查询结果，包含充值订单列表和分页信息
     */
    Page<PaymentOrder> selectByCustomerNo(@Param("customerNo") String customerNo,
                                          @Param("status") String status,
                                          @Param("platform") String platform);

    /**
     * 查询过期的待支付订单
     * 
     * <p>查询已过期但状态仍为PENDING的订单，用于定时任务处理。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>status = 'PENDING'</li>
     *   <li>expireTime < 当前时间</li>
     *   <li>按过期时间升序排列</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>定时任务处理过期订单</li>
     *   <li>订单状态同步</li>
     *   <li>数据清理</li>
     * </ul>
     * 
     * <h4>处理建议：</h4>
     * <ul>
     *   <li>批量更新状态为EXPIRED</li>
     *   <li>释放相关资源</li>
     *   <li>记录处理日志</li>
     * </ul>
     * 
     * @param currentTime 当前时间，用于比较过期时间
     * @param limit 查询限制数量，防止一次处理过多数据
     * @return 过期订单列表，按过期时间升序排列
     */
    List<PaymentOrder> selectExpiredOrders(@Param("currentTime") LocalDateTime currentTime,
                                           @Param("limit") Integer limit);

    /**
     * 按时间范围查询充值订单
     * 
     * <p>查询指定时间范围内的充值订单记录，支持多种筛选条件。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>时间范围：startTime <= createTime <= endTime</li>
     *   <li>用户过滤：customerNo（可选）</li>
     *   <li>订单状态：status（可选）</li>
     *   <li>支付平台：platform（可选）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>财务对账</li>
     *   <li>数据统计分析</li>
     *   <li>订单报表生成</li>
     *   <li>审计查询</li>
     * </ul>
     * 
     * <h4>性能优化：</h4>
     * <ul>
     *   <li>基于时间索引查询</li>
     *   <li>建议时间范围不超过3个月</li>
     *   <li>支持分页查询</li>
     * </ul>
     * 
     * @param startTime 开始时间，不能为null
     * @param endTime 结束时间，不能为null，且必须大于等于startTime
     * @param customerNo 用户编号过滤，null表示查询所有用户
     * @param status 订单状态过滤，null表示查询所有状态
     * @param platform 支付平台过滤，null表示查询所有平台
     * @return 分页查询结果，包含充值订单列表和分页信息
     */
    Page<PaymentOrder> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                         @Param("endTime") LocalDateTime endTime,
                                         @Param("customerNo") String customerNo,
                                         @Param("status") String status,
                                         @Param("platform") String platform);

    /**
     * 统计充值订单数据
     * 
     * <p>统计指定条件下的充值订单数量和金额。</p>
     * 
     * <h4>统计维度：</h4>
     * <ul>
     *   <li>按状态分组统计</li>
     *   <li>按平台分组统计</li>
     *   <li>按时间范围统计</li>
     * </ul>
     * 
     * <h4>返回字段：</h4>
     * <ul>
     *   <li>status/platform - 分组字段</li>
     *   <li>orderCount - 订单数量</li>
     *   <li>totalAmount - 总金额（分）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>业务数据统计</li>
     *   <li>财务报表生成</li>
     *   <li>平台分析</li>
     * </ul>
     * 
     * @param startTime 开始时间，null表示不限制开始时间
     * @param endTime 结束时间，null表示不限制结束时间
     * @param customerNo 用户编号过滤，null表示查询所有用户
     * @param groupBy 分组字段：status、platform，不能为null
     * @return 统计结果列表，每个元素包含分组字段和统计数据
     */
    List<PaymentOrder> statisticsOrders(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime,
                                        @Param("customerNo") String customerNo,
                                        @Param("groupBy") String groupBy);

    /**
     * 检查订单号是否存在
     * 
     * <p>检查指定的订单号是否已经存在，用于创建订单时的唯一性检查。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>创建订单前的唯一性检查</li>
     *   <li>订单号生成时的重复检查</li>
     *   <li>防止重复提交</li>
     * </ul>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>订单号全局唯一</li>
     *   <li>创建订单前必须检查</li>
     * </ul>
     * 
     * @param orderNo 订单号，不能为null或空字符串
     * @return 存在的记录数，0表示不存在，大于0表示存在
     */
    int countByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 批量更新过期订单状态
     * 
     * <p>批量将过期的待支付订单状态更新为EXPIRED。</p>
     * 
     * <h4>更新条件：</h4>
     * <ul>
     *   <li>status = 'PENDING'</li>
     *   <li>expireTime < 当前时间</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>定时任务批量处理</li>
     *   <li>订单状态维护</li>
     *   <li>数据清理</li>
     * </ul>
     * 
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>建议分批处理，避免长时间锁表</li>
     *   <li>需要记录处理日志</li>
     *   <li>可能需要释放相关资源</li>
     * </ul>
     * 
     * @param currentTime 当前时间，用于比较过期时间
     * @param limit 批量处理限制数量，防止一次更新过多数据
     * @return 影响的行数，等于更新的订单数量
     */
    int batchUpdateExpiredOrders(@Param("orderNos") List<String> orderNos);
    /**
     * 根据时间范围和平台查询订单
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param platform 平台
     * @return 订单列表
     */
    List<PaymentOrder> selectByTimeRangeAndPlatform(@Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime,
                                                  @Param("platform") String platform);

    /**
     * 根据业务单号查询所有已成功支付的订单（含首付款+补差款）
     * 用于退款时聚合该业务单下所有可退流水，防止漏退补差单
     *
     * @param bizNo 业务单号
     * @return 按创建时间升序排列的支付订单列表
     */
    List<PaymentOrder> selectSuccessfulByBizNo(@Param("bizNo") String bizNo);

    /**
     * 根据订单号加行锁查询支付订单（退款并发安全）
     * 配合 SELECT ... FOR UPDATE 防止并发退款超额
     *
     * @param orderNo 订单号
     * @return 支付订单实体，带行锁
     */
    PaymentOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
