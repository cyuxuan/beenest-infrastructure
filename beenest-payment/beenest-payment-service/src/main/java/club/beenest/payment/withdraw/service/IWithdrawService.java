package club.beenest.payment.withdraw.service;

import club.beenest.payment.withdraw.dto.WithdrawAuditDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.withdraw.dto.WithdrawResultDTO;
import club.beenest.payment.withdraw.domain.entity.WithdrawRequest;
import com.github.pagehelper.Page;

/**
 * 提现服务接口
 * 定义提现相关的业务逻辑接口
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>提现申请管理 - 创建、查询、审核提现申请</li>
 *   <li>提现处理 - 对接支付宝、银行等提现渠道</li>
 *   <li>红包兑换 - 红包余额转换为钱包余额</li>
 *   <li>资金安全控制 - 余额冻结、解冻、扣减</li>
 * </ul>
 * 
 * <h3>提现类型：</h3>
 * <ul>
 *   <li>ALIPAY - 支付宝提现</li>
 *   <li>BANK_CARD - 银行卡提现</li>
 * </ul>
 * 
 * <h3>申请状态：</h3>
 * <ul>
 *   <li>PENDING - 待审核</li>
 *   <li>APPROVED - 已审核</li>
 *   <li>PROCESSING - 处理中</li>
 *   <li>SUCCESS - 成功</li>
 *   <li>FAILED - 失败</li>
 *   <li>CANCELLED - 已取消</li>
 * </ul>
 * 
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>余额充足性检查</li>
 *   <li>资金冻结机制</li>
 *   <li>审核流程控制</li>
 *   <li>风控规则验证</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
public interface IWithdrawService {

    /**
     * 创建提现申请
     * 
     * <p>创建用户提现申请，冻结相应金额并等待审核。</p>
     * 
     * <h4>创建流程：</h4>
     * <ol>
     *   <li>验证用户和提现参数</li>
     *   <li>检查用户是否有处理中的申请</li>
     *   <li>验证账户信息格式</li>
     *   <li>检查余额是否充足</li>
     *   <li>计算手续费和实际到账金额</li>
     *   <li>冻结提现金额</li>
     *   <li>创建提现申请记录</li>
     *   <li>记录交易流水</li>
     * </ol>
     * 
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>用户同时只能有一个处理中的提现申请</li>
     *   <li>提现金额不能超过可用余额</li>
     *   <li>最小提现金额为100元</li>
     *   <li>需要扣除相应的手续费</li>
     * </ul>
     * 
     * <h4>手续费规则：</h4>
     * <ul>
     *   <li>支付宝提现：2元手续费</li>
     *   <li>银行卡提现：5元手续费</li>
     * </ul>
     * 
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>requestNo - 申请号</li>
     *   <li>amount - 提现金额（分）</li>
     *   <li>feeAmount - 手续费金额（分）</li>
     *   <li>actualAmount - 实际到账金额（分）</li>
     *   <li>status - 申请状态</li>
     *   <li>createTime - 申请时间</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为空
     * @param withdrawRequest 提现请求参数
     * @return 提现申请信息
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果创建申请失败
     */
    WithdrawResultDTO createWithdrawRequest(String customerNo, WithdrawRequestDTO withdrawRequest);

    /**
     * 审核提现申请
     * 
     * <p>管理员审核提现申请，决定是否通过申请。</p>
     * 
     * <h4>审核流程：</h4>
     * <ol>
     *   <li>验证申请状态</li>
     *   <li>检查审核权限</li>
     *   <li>执行风控检查</li>
     *   <li>更新申请状态</li>
     *   <li>记录审核信息</li>
     *   <li>如果拒绝，解冻资金</li>
     * </ol>
     * 
     * <h4>审核规则：</h4>
     * <ul>
     *   <li>只有PENDING状态的申请可以审核</li>
     *   <li>需要验证用户身份信息</li>
     *   <li>检查账户安全状态</li>
     *   <li>验证提现频率限制</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为空
     * @param approved 是否通过审核
     * @param auditUser 审核人，不能为空
     * @param auditRemark 审核备注，可为空
     * @return 审核结果
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果审核失败
     */
    boolean auditWithdrawRequest(String requestNo, boolean approved, String auditUser, String auditRemark);

    /**
     * 处理提现申请
     * 
     * <p>处理已审核通过的提现申请，调用第三方接口执行提现。</p>
     * 
     * <h4>处理流程：</h4>
     * <ol>
     *   <li>验证申请状态</li>
     *   <li>更新状态为PROCESSING</li>
     *   <li>调用第三方提现接口</li>
     *   <li>处理提现结果</li>
     *   <li>更新申请状态</li>
     *   <li>扣减冻结余额</li>
     *   <li>记录交易流水</li>
     * </ol>
     * 
     * <h4>第三方接口：</h4>
     * <ul>
     *   <li>支付宝提现 - 调用支付宝转账接口</li>
     *   <li>银行卡提现 - 调用银行转账接口</li>
     * </ul>
     * 
     * @param requestNo 申请号，不能为空
     * @return 处理结果
     * @throws IllegalArgumentException 如果申请号为空
     * @throws RuntimeException 如果处理失败
     */
    boolean processWithdrawRequest(String requestNo);

    /**
     * 取消提现申请
     * 
     * <p>取消待审核或已审核的提现申请，解冻相应资金。</p>
     * 
     * <h4>取消条件：</h4>
     * <ul>
     *   <li>申请状态为PENDING或APPROVED</li>
     *   <li>用户有权限取消该申请</li>
     *   <li>或管理员强制取消</li>
     * </ul>
     * 
     * <h4>取消流程：</h4>
     * <ol>
     *   <li>验证申请状态</li>
     *   <li>检查取消权限</li>
     *   <li>更新申请状态为CANCELLED</li>
     *   <li>解冻冻结资金</li>
     *   <li>记录取消原因</li>
     * </ol>
     * 
     * @param customerNo 用户编号，不能为空
     * @param requestNo 申请号，不能为空
     * @param cancelReason 取消原因，可为空
     * @return 取消结果，true表示取消成功，false表示取消失败
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果取消过程中发生异常
     */
    boolean cancelWithdrawRequest(String customerNo, String requestNo, String cancelReason);

    /**
     * 查询提现申请状态
     *
     * <p>查询指定提现申请的当前状态和处理进度。</p>
     *
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>申请基本信息 - 申请号、金额、类型等</li>
     *   <li>状态信息 - 当前状态、审核信息、处理结果</li>
     *   <li>时间信息 - 申请时间、审核时间、处理时间</li>
     *   <li>账户信息 - 脱敏后的收款账户信息</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为空
     * @param requestNo 申请号，不能为空
     * @return 提现申请详细信息
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果查询失败
     */
    WithdrawResultDTO getWithdrawRequestStatus(String customerNo, String requestNo);
/**
     * 查询提现申请
     *
     * @param query 查询参数
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 申请分页列表
     */
    Page<WithdrawRequest> queryRequests(WithdrawRequestQueryDTO query, int pageNum, int pageSize);

    /**
     * 审核提现申请
     *
     * @param audit 审核参数
     */
    void auditRequest(WithdrawAuditDTO audit);
}