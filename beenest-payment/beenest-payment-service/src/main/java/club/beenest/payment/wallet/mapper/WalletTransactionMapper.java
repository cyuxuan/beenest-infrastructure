package club.beenest.payment.wallet.mapper;

import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.wallet.domain.entity.WalletTransaction;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 钱包交易流水数据访问接口
 * 提供钱包交易流水相关的数据库操作方法
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>交易流水记录管理</li>
 *   <li>交易历史查询</li>
 *   <li>统计分析支持</li>
 *   <li>审计日志功能</li>
 * </ul>
 * 
 * <h3>查询特性：</h3>
 * <ul>
 *   <li>支持分页查询</li>
 *   <li>支持多条件筛选</li>
 *   <li>支持时间范围查询</li>
 *   <li>支持交易类型过滤</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Mapper
public interface WalletTransactionMapper {

    /**
     * 根据主键ID查询交易流水
     * 
     * <p>通过交易流水的主键ID查询完整的交易信息。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>交易详情查询</li>
     *   <li>数据关联查询</li>
     *   <li>管理后台操作</li>
     * </ul>
     * 
     * @param id 交易流水主键ID，不能为null
     * @return 交易流水实体对象，如果不存在则返回null
     */
    WalletTransaction selectById(@Param("id") Long id);

    /**
     * 根据交易流水号查询交易信息
     * 
     * <p>通过交易流水号查询完整的交易信息，这是最常用的查询方式。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>业务逻辑查询</li>
     *   <li>API接口调用</li>
     *   <li>交易状态查询</li>
     *   <li>问题排查和审计</li>
     * </ul>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>基于唯一索引，查询效率高</li>
     *   <li>返回完整的交易信息</li>
     *   <li>支持所有状态的交易</li>
     * </ul>
     * 
     * @param transactionNo 交易流水号，不能为null或空字符串
     * @return 交易流水实体对象，如果不存在则返回null
     */
    WalletTransaction selectByTransactionNo(@Param("transactionNo") String transactionNo);

    /**
     * 插入新的交易流水记录
     * 
     * <p>创建新的交易流水记录，记录钱包资金变动的详细信息。</p>
     * 
     * <h4>记录规则：</h4>
     * <ul>
     *   <li>交易流水号必须唯一</li>
     *   <li>必须记录交易前后余额</li>
     *   <li>交易金额正数表示收入，负数表示支出</li>
     *   <li>必须关联钱包和用户</li>
     * </ul>
     * 
     * <h4>必填字段：</h4>
     * <ul>
     *   <li>transactionNo - 交易流水号</li>
     *   <li>walletNo - 钱包编号</li>
     *   <li>customerNo - 用户编号</li>
     *   <li>transactionType - 交易类型</li>
     *   <li>amount - 交易金额</li>
     *   <li>beforeBalance - 交易前余额</li>
     *   <li>afterBalance - 交易后余额</li>
     *   <li>description - 交易描述</li>
     * </ul>
     * 
     * @param transaction 交易流水实体对象，必须包含所有必填字段
     * @return 影响的行数，成功插入返回1
     */
    int insert(WalletTransaction transaction);

    /**
     * 批量插入交易流水记录
     * 
     * <p>批量创建交易流水记录，提高插入效率。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>批量数据导入</li>
     *   <li>系统初始化</li>
     *   <li>数据迁移</li>
     * </ul>
     * 
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>所有记录必须符合插入规则</li>
     *   <li>交易流水号必须全局唯一</li>
     *   <li>建议单次批量不超过1000条</li>
     * </ul>
     * 
     * @param transactions 交易流水实体对象列表，不能为null或空
     * @return 影响的行数，等于成功插入的记录数
     */
    int batchInsert(@Param("transactions") List<WalletTransaction> transactions);

    /**
     * 分页查询用户交易历史
     * 
     * <p>分页查询指定用户的交易历史记录，按时间倒序排列。</p>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>支持PageHelper分页</li>
     *   <li>按创建时间倒序排列</li>
     *   <li>只查询成功的交易</li>
     *   <li>支持交易类型过滤</li>
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
     * Page&lt;WalletTransaction&gt; result = walletTransactionMapper.selectByCustomerNo(customerNo, null);
     * </pre>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @param transactionType 交易类型过滤，null表示查询所有类型
     * @return 分页查询结果，包含交易流水列表和分页信息
     */
    Page<WalletTransaction> selectByCustomerNo(@Param("customerNo") String customerNo,
                                               @Param("transactionType") String transactionType);

    /**
     * 分页查询钱包交易历史
     * 
     * <p>分页查询指定钱包的交易历史记录，按时间倒序排列。</p>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>支持PageHelper分页</li>
     *   <li>按创建时间倒序排列</li>
     *   <li>支持状态过滤</li>
     *   <li>支持交易类型过滤</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>钱包详情页面</li>
     *   <li>管理后台查询</li>
     *   <li>数据分析统计</li>
     * </ul>
     * 
     * @param walletNo 钱包编号，不能为null或空字符串
     * @param transactionType 交易类型过滤，null表示查询所有类型
     * @param status 交易状态过滤，null表示查询所有状态
     * @return 分页查询结果，包含交易流水列表和分页信息
     */
    Page<WalletTransaction> selectByWalletNo(@Param("walletNo") String walletNo,
                                             @Param("transactionType") String transactionType,
                                             @Param("status") String status);

    /**
     * 按时间范围查询交易记录
     * 
     * <p>查询指定时间范围内的交易记录，支持多种筛选条件。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>时间范围：startTime <= createTime <= endTime</li>
     *   <li>用户过滤：customerNo（可选）</li>
     *   <li>交易类型：transactionType（可选）</li>
     *   <li>交易状态：status（可选）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>财务对账</li>
     *   <li>数据统计分析</li>
     *   <li>交易报表生成</li>
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
     * @param transactionType 交易类型过滤，null表示查询所有类型
     * @param status 交易状态过滤，null表示查询所有状态
     * @return 分页查询结果，包含交易流水列表和分页信息
     */
    Page<WalletTransaction> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("customerNo") String customerNo,
                                              @Param("transactionType") String transactionType,
                                              @Param("status") String status);

    /**
     * 根据关联单号查询交易记录
     * 
     * <p>查询与指定业务单号关联的交易记录。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>订单支付查询</li>
     *   <li>红包兑换查询</li>
     *   <li>退款记录查询</li>
     *   <li>业务关联分析</li>
     * </ul>
     * 
     * <h4>关联类型：</h4>
     * <ul>
     *   <li>ORDER - 订单</li>
     *   <li>RED_PACKET - 红包</li>
     *   <li>COUPON - 优惠券</li>
     *   <li>PAYMENT_ORDER - 充值订单</li>
     *   <li>WITHDRAW_REQUEST - 提现申请</li>
     * </ul>
     * 
     * @param referenceNo 关联单号，不能为null或空字符串
     * @param referenceType 关联类型，null表示查询所有类型
     * @return 交易流水列表，按创建时间倒序排列
     */
    List<WalletTransaction> selectByReferenceNo(@Param("referenceNo") String referenceNo,
                                                @Param("referenceType") String referenceType);

    /**
     * 统计用户交易金额
     * 
     * <p>统计指定用户在指定时间范围内的交易金额。</p>
     * 
     * <h4>统计维度：</h4>
     * <ul>
     *   <li>按交易类型分组统计</li>
     *   <li>只统计成功的交易</li>
     *   <li>收入和支出分别统计</li>
     * </ul>
     * 
     * <h4>返回字段：</h4>
     * <ul>
     *   <li>transactionType - 交易类型</li>
     *   <li>totalAmount - 总金额（分）</li>
     *   <li>transactionCount - 交易笔数</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>用户消费统计</li>
     *   <li>财务报表生成</li>
     *   <li>用户行为分析</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @param startTime 开始时间，null表示不限制开始时间
     * @param endTime 结束时间，null表示不限制结束时间
     * @return 统计结果列表，每个元素包含交易类型和统计数据
     */
    List<WalletTransaction> statisticsByCustomerNo(@Param("customerNo") String customerNo,
                                                   @Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime);

    /**
     * 检查交易流水号是否存在
     * 
     * <p>检查指定的交易流水号是否已经存在，用于创建交易时的唯一性检查。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>创建交易前的唯一性检查</li>
     *   <li>交易流水号生成时的重复检查</li>
     *   <li>防止重复提交</li>
     * </ul>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>交易流水号全局唯一</li>
     *   <li>创建交易前必须检查</li>
     * </ul>
     * 
     * @param transactionNo 交易流水号，不能为null或空字符串
     * @return 存在的记录数，0表示不存在，大于0表示存在
     */
    int countByTransactionNo(@Param("transactionNo") String transactionNo);

    /**
     * 根据关联单号检查交易是否存在（幂等性检查）
     * 
     * <p>检查指定的关联单号是否已经有交易记录，用于防止重复处理。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>充值回调幂等性检查</li>
     *   <li>退款幂等性检查</li>
     *   <li>提现幂等性检查</li>
     *   <li>防止重复处理同一笔业务</li>
     * </ul>
     * 
     * <h4>安全特性：</h4>
     * <ul>
     *   <li>防止重复充值到账</li>
     *   <li>防止重复扣款</li>
     *   <li>确保交易唯一性</li>
     * </ul>
     * 
     * @param referenceNo 关联单号，不能为null或空字符串
     * @return 存在的记录数，0表示不存在，大于0表示存在
     */
    int countByReferenceNo(@Param("referenceNo") String referenceNo);

    /**
     * 更新交易状态
     * 
     * <p>更新指定交易的状态，用于交易状态变更。</p>
     * 
     * <h4>状态说明：</h4>
     * <ul>
     *   <li>SUCCESS - 成功</li>
     *   <li>FAILED - 失败</li>
     *   <li>PROCESSING - 处理中</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>异步交易状态更新</li>
     *   <li>交易失败处理</li>
     *   <li>交易补偿操作</li>
     * </ul>
     * 
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>只能更新状态字段</li>
     *   <li>不能修改金额等关键信息</li>
     *   <li>需要记录状态变更日志</li>
     * </ul>
     * 
     * @param transactionNo 交易流水号，不能为null或空字符串
     * @param status 新的状态值，不能为null
     * @param remark 状态变更备注，可以为null
     * @return 影响的行数，成功更新返回1
     */
    int updateStatus(@Param("transactionNo") String transactionNo,
                     @Param("status") String status,
                     @Param("remark") String remark);
    /**
     * 根据查询条件分页查询交易记录
     * 
     * @param query 查询条件
     * @return 分页查询结果
     */
    Page<WalletTransaction> selectByQuery(TransactionQueryDTO query);

    /**
     * 查询指定钱包所有成功交易金额之和（用于对账）
     *
     * @param walletNo 钱包编号
     * @return 成功交易金额之和（分），无记录时返回0
     */
    Long selectSumAmountByWalletNo(@Param("walletNo") String walletNo);
}