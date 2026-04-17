package club.beenest.payment.mapper;

import club.beenest.payment.object.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.object.entity.WithdrawRequest;
import com.github.pagehelper.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提现申请数据访问接口
 * 提供提现申请相关的数据库操作方法
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>提现申请管理</li>
 *   <li>审核流程支持</li>
 *   <li>提现状态跟踪</li>
 *   <li>申请查询和统计</li>
 * </ul>
 * 
 * <h3>申请状态管理：</h3>
 * <ul>
 *   <li>PENDING - 待审核</li>
 *   <li>APPROVED - 已审核</li>
 *   <li>PROCESSING - 处理中</li>
 *   <li>SUCCESS - 成功</li>
 *   <li>FAILED - 失败</li>
 *   <li>CANCELLED - 已取消</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Mapper
public interface WithdrawRequestMapper {

    /**
     * 根据主键ID查询提现申请
     * 
     * <p>通过提现申请的主键ID查询完整的申请信息。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>申请详情查询</li>
     *   <li>数据关联查询</li>
     *   <li>管理后台操作</li>
     * </ul>
     * 
     * @param id 提现申请主键ID，不能为null
     * @return 提现申请实体对象，如果不存在则返回null
     */
    WithdrawRequest selectById(@Param("id") Long id);

    /**
     * 根据申请号查询提现申请
     * 
     * <p>通过申请号查询完整的提现申请信息，这是最常用的查询方式。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>业务逻辑查询</li>
     *   <li>API接口调用</li>
     *   <li>申请状态查询</li>
     *   <li>问题排查和审计</li>
     * </ul>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>基于唯一索引，查询效率高</li>
     *   <li>返回完整的申请信息</li>
     *   <li>支持所有状态的申请</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为null或空字符串
     * @return 提现申请实体对象，如果不存在则返回null
     */
    WithdrawRequest selectByRequestNo(@Param("requestNo") String requestNo);

    /**
     * 根据查询条件查询提现申请
     *
     * @param query 查询参数
     * @return 提现申请列表
     */
    List<WithdrawRequest> selectByQuery(WithdrawRequestQueryDTO query);

    /**
     * 插入新的提现申请记录
     * 
     * <p>创建新的提现申请记录，设置初始状态和申请信息。</p>
     * 
     * <h4>创建规则：</h4>
     * <ul>
     *   <li>申请号必须唯一</li>
     *   <li>初始状态为PENDING</li>
     *   <li>提现金额必须大于0</li>
     *   <li>必须包含完整的账户信息</li>
     * </ul>
     * 
     * <h4>必填字段：</h4>
     * <ul>
     *   <li>requestNo - 申请号</li>
     *   <li>customerNo - 用户编号</li>
     *   <li>walletNo - 钱包编号</li>
     *   <li>amount - 提现金额</li>
     *   <li>withdrawType - 提现类型</li>
     *   <li>accountType - 账户类型</li>
     *   <li>accountName - 账户姓名</li>
     *   <li>accountNumber - 账户号码</li>
     *   <li>feeAmount - 手续费金额</li>
     *   <li>actualAmount - 实际到账金额</li>
     * </ul>
     * 
     * <h4>自动设置字段：</h4>
     * <ul>
     *   <li>status - PENDING</li>
     *   <li>createTime - 当前时间</li>
     *   <li>updateTime - 当前时间</li>
     * </ul>
     * 
     * @param withdrawRequest 提现申请实体对象，必须包含所有必填字段
     * @return 影响的行数，成功插入返回1
     */
    int insert(WithdrawRequest withdrawRequest);

    /**
     * 更新提现申请信息
     * 
     * <p>更新提现申请的基本信息，不包括状态字段。</p>
     * 
     * <h4>可更新字段：</h4>
     * <ul>
     *   <li>accountName - 账户姓名</li>
     *   <li>accountNumber - 账户号码</li>
     *   <li>bankName - 银行名称</li>
     *   <li>bankBranch - 开户行支行</li>
     *   <li>remark - 备注信息</li>
     * </ul>
     * 
     * <h4>不可更新字段：</h4>
     * <ul>
     *   <li>status - 申请状态（需要使用专门的方法）</li>
     *   <li>amount - 提现金额</li>
     *   <li>customerNo - 用户编号</li>
     *   <li>walletNo - 钱包编号</li>
     * </ul>
     * 
     * <h4>限制条件：</h4>
     * <ul>
     *   <li>只有PENDING状态的申请可以更新</li>
     *   <li>已审核的申请不能修改</li>
     * </ul>
     * 
     * @param withdrawRequest 提现申请实体对象，必须包含requestNo
     * @return 影响的行数，成功更新返回1
     */
    int updateByRequestNo(WithdrawRequest withdrawRequest);

    /**
     * 审核提现申请
     * 
     * <p>更新提现申请的审核状态和审核信息。</p>
     * 
     * <h4>状态流转：</h4>
     * <ul>
     *   <li>PENDING → APPROVED（审核通过）</li>
     *   <li>PENDING → CANCELLED（审核拒绝）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>管理员审核操作</li>
     *   <li>自动审核处理</li>
     *   <li>风控审核</li>
     * </ul>
     * 
     * <h4>更新字段：</h4>
     * <ul>
     *   <li>status - 审核结果状态</li>
     *   <li>auditUser - 审核人</li>
     *   <li>auditTime - 审核时间</li>
     *   <li>auditRemark - 审核备注</li>
     *   <li>updateTime - 更新时间</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为null或空字符串
     * @param status 审核结果状态，APPROVED或CANCELLED
     * @param auditUser 审核人，不能为null或空字符串
     * @param auditRemark 审核备注，可以为null
     * @return 影响的行数，成功更新返回1
     */
    int auditRequest(@Param("requestNo") String requestNo,
                     @Param("status") String status,
                     @Param("auditUser") String auditUser,
                     @Param("auditRemark") String auditRemark);

    /**
     * 更新申请处理状态
     * 
     * <p>更新提现申请的处理状态和处理结果。</p>
     * 
     * <h4>状态流转：</h4>
     * <ul>
     *   <li>APPROVED → PROCESSING（开始处理）</li>
     *   <li>PROCESSING → SUCCESS（处理成功）</li>
     *   <li>PROCESSING → FAILED（处理失败）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>提现处理系统更新状态</li>
     *   <li>第三方支付回调</li>
     *   <li>手动处理结果更新</li>
     * </ul>
     * 
     * <h4>更新字段：</h4>
     * <ul>
     *   <li>status - 处理状态</li>
     *   <li>processTime - 处理完成时间</li>
     *   <li>processResult - 处理结果</li>
     *   <li>thirdPartyOrderNo - 第三方订单号</li>
     *   <li>updateTime - 更新时间</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为null或空字符串
     * @param status 处理状态，PROCESSING、SUCCESS或FAILED
     * @param processResult 处理结果描述，可以为null
     * @param thirdPartyOrderNo 第三方订单号，可以为null
     * @return 影响的行数，成功更新返回1
     */
    int updateProcessStatus(@Param("requestNo") String requestNo,
                            @Param("status") String status,
                            @Param("processResult") String processResult,
                            @Param("thirdPartyOrderNo") String thirdPartyOrderNo);

    /**
     * 分页查询用户提现申请
     * 
     * <p>分页查询指定用户的提现申请记录，按时间倒序排列。</p>
     * 
     * <h4>查询特性：</h4>
     * <ul>
     *   <li>支持PageHelper分页</li>
     *   <li>按创建时间倒序排列</li>
     *   <li>支持状态过滤</li>
     *   <li>支持提现类型过滤</li>
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
     * Page&lt;WithdrawRequest&gt; result = withdrawRequestMapper.selectByCustomerNo(customerNo, null, null);
     * </pre>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @param status 申请状态过滤，null表示查询所有状态
     * @param withdrawType 提现类型过滤，null表示查询所有类型
     * @return 分页查询结果，包含提现申请列表和分页信息
     */
    Page<WithdrawRequest> selectByCustomerNo(@Param("customerNo") String customerNo,
                                             @Param("status") String status,
                                             @Param("withdrawType") String withdrawType);

    /**
     * 查询待审核的提现申请
     * 
     * <p>查询状态为PENDING的提现申请，用于审核管理。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>status = 'PENDING'</li>
     *   <li>按创建时间升序排列（先申请的先审核）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>管理后台审核列表</li>
     *   <li>自动审核处理</li>
     *   <li>审核工作量统计</li>
     * </ul>
     * 
     * <h4>分页支持：</h4>
     * <ul>
     *   <li>支持PageHelper分页</li>
     *   <li>建议设置合理的页面大小</li>
     * </ul>
     * 
     * @return 分页查询结果，包含待审核申请列表和分页信息
     */
    Page<WithdrawRequest> selectPendingRequests();

    /**
     * 查询已审核待处理的提现申请
     * 
     * <p>查询状态为APPROVED的提现申请，用于提现处理。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>status = 'APPROVED'</li>
     *   <li>按审核时间升序排列（先审核的先处理）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>提现处理系统</li>
     *   <li>批量提现处理</li>
     *   <li>处理进度跟踪</li>
     * </ul>
     * 
     * @return 分页查询结果，包含待处理申请列表和分页信息
     */
    Page<WithdrawRequest> selectApprovedRequests();

    /**
     * 按时间范围查询提现申请
     * 
     * <p>查询指定时间范围内的提现申请记录，支持多种筛选条件。</p>
     * 
     * <h4>查询条件：</h4>
     * <ul>
     *   <li>时间范围：startTime <= createTime <= endTime</li>
     *   <li>用户过滤：customerNo（可选）</li>
     *   <li>申请状态：status（可选）</li>
     *   <li>提现类型：withdrawType（可选）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>财务对账</li>
     *   <li>数据统计分析</li>
     *   <li>提现报表生成</li>
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
     * @param status 申请状态过滤，null表示查询所有状态
     * @param withdrawType 提现类型过滤，null表示查询所有类型
     * @return 分页查询结果，包含提现申请列表和分页信息
     */
    Page<WithdrawRequest> selectByTimeRange(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime,
                                            @Param("customerNo") String customerNo,
                                            @Param("status") String status,
                                            @Param("withdrawType") String withdrawType);

    /**
     * 统计提现申请数据
     * 
     * <p>统计指定条件下的提现申请数量和金额。</p>
     * 
     * <h4>统计维度：</h4>
     * <ul>
     *   <li>按状态分组统计</li>
     *   <li>按提现类型分组统计</li>
     *   <li>按时间范围统计</li>
     * </ul>
     * 
     * <h4>返回字段：</h4>
     * <ul>
     *   <li>status/withdrawType - 分组字段</li>
     *   <li>requestCount - 申请数量</li>
     *   <li>totalAmount - 总金额（分）</li>
     *   <li>totalFeeAmount - 总手续费（分）</li>
     *   <li>totalActualAmount - 总实际金额（分）</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>业务数据统计</li>
     *   <li>财务报表生成</li>
     *   <li>提现类型分析</li>
     * </ul>
     * 
     * @param startTime 开始时间，null表示不限制开始时间
     * @param endTime 结束时间，null表示不限制结束时间
     * @param customerNo 用户编号过滤，null表示查询所有用户
     * @param groupBy 分组字段：status、withdrawType，不能为null
     * @return 统计结果列表，每个元素包含分组字段和统计数据
     */
    List<WithdrawRequest> statisticsRequests(@Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime,
                                              @Param("customerNo") String customerNo,
                                              @Param("groupBy") String groupBy);

    /**
     * 检查申请号是否存在
     * 
     * <p>检查指定的申请号是否已经存在，用于创建申请时的唯一性检查。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>创建申请前的唯一性检查</li>
     *   <li>申请号生成时的重复检查</li>
     *   <li>防止重复提交</li>
     * </ul>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>申请号全局唯一</li>
     *   <li>创建申请前必须检查</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为null或空字符串
     * @return 存在的记录数，0表示不存在，大于0表示存在
     */
    int countByRequestNo(@Param("requestNo") String requestNo);

    /**
     * 检查用户是否有处理中的提现申请
     * 
     * <p>检查指定用户是否有状态为PENDING、APPROVED或PROCESSING的提现申请。</p>
     * 
     * <h4>检查状态：</h4>
     * <ul>
     *   <li>PENDING - 待审核</li>
     *   <li>APPROVED - 已审核</li>
     *   <li>PROCESSING - 处理中</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>创建新申请前的检查</li>
     *   <li>防止重复申请</li>
     *   <li>业务规则验证</li>
     * </ul>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>用户同时只能有一个处理中的申请</li>
     *   <li>必须等待当前申请完成才能提交新申请</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为null或空字符串
     * @return 处理中的申请数量，0表示没有，大于0表示有处理中的申请
     */
    int countProcessingByCustomerNo(@Param("customerNo") String customerNo);

    /**
     * 取消提现申请
     * 
     * <p>将指定的提现申请状态更新为CANCELLED。</p>
     * 
     * <h4>取消条件：</h4>
     * <ul>
     *   <li>只有PENDING或APPROVED状态的申请可以取消</li>
     *   <li>PROCESSING和已完成的申请不能取消</li>
     * </ul>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>用户主动取消申请</li>
     *   <li>管理员取消申请</li>
     *   <li>系统自动取消（如风控）</li>
     * </ul>
     * 
     * <h4>注意事项：</h4>
     * <ul>
     *   <li>取消后需要解冻相关资金</li>
     *   <li>需要记录取消原因</li>
     *   <li>不可逆操作</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为null或空字符串
     * @param remark 取消原因，可以为null
     * @return 影响的行数，成功取消返回1，不满足条件返回0
     */
    int cancelRequest(@Param("requestNo") String requestNo,
                      @Param("remark") String remark);
}