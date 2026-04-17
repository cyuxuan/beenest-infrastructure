package club.beenest.payment.service;

import club.beenest.payment.object.dto.WalletBalanceDTO;
import club.beenest.payment.object.dto.TransactionHistoryDTO;
import club.beenest.payment.object.dto.TransactionQueryDTO;
import club.beenest.payment.object.dto.WalletAdminQueryDTO;
import club.beenest.payment.object.entity.Wallet;
import club.beenest.payment.object.entity.WalletTransaction;
import com.github.pagehelper.Page;

import java.math.BigDecimal;
import java.util.List;

/**
 * 钱包服务接口
 * 支持多租户：通过 bizType 参数隔离不同业务系统的钱包
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>余额操作 - 增加余额、扣减余额，带乐观锁和哈希校验</li>
 *   <li>钱包管理 - 创建、查询、获取或创建钱包</li>
 *   <li>交易历史 - 分页查询交易记录</li>
 * </ul>
 *
 * @author System
 * @since 2026-01-26
 */
public interface IWalletService {

    // ==================== 基础余额操作 ====================

    /**
     * 查询用户余额（元）
     *
     * @param customerNo 用户编号，不能为空
     * @param bizType    业务类型，为空时使用默认值
     * @return 用户余额（元），钱包不存在时返回零
     * @throws IllegalArgumentException 如果 customerNo 为空
     */
    BigDecimal getBalance(String customerNo, String bizType);

    /**
     * 增加用户余额
     *
     * <p>幂等：如果 referenceNo 已存在，拒绝重复处理。</p>
     *
     * @param customerNo     用户编号，不能为空
     * @param bizType        业务类型，为空时使用默认值
     * @param amount         金额（元），必须大于0
     * @param description    交易描述，不能为空
     * @param transactionType 交易类型（RECHARGE, WITHDRAW, PAYMENT 等）
     * @param referenceNo    关联单号，用于幂等控制
     * @throws IllegalArgumentException 如果参数无效
     * @throws BusinessException 如果钱包状态异常或达到最大重试次数
     */
    void addBalance(String customerNo, String bizType, BigDecimal amount, String description,
                   String transactionType, String referenceNo);

    /**
     * 扣减用户余额
     *
     * <p>幂等：如果 referenceNo 已存在，返回 false 而不抛异常。</p>
     *
     * @param customerNo     用户编号，不能为空
     * @param bizType        业务类型，为空时使用默认值
     * @param amount         金额（元），必须大于0
     * @param description    交易描述，不能为空
     * @param transactionType 交易类型
     * @param referenceNo    关联单号，用于幂等控制
     * @return true 表示扣减成功，false 表示余额不足或重复交易
     * @throws IllegalArgumentException 如果参数无效
     * @throws BusinessException 如果达到最大重试次数
     */
    boolean deductBalance(String customerNo, String bizType, BigDecimal amount, String description,
                         String transactionType, String referenceNo);

    // ==================== 钱包管理 ====================

    /**
     * 创建用户钱包
     *
     * <p>利用数据库 UNIQUE(customer_no, biz_type) 约束防止重复创建。</p>
     *
     * @param customerNo 用户编号，不能为空
     * @param bizType    业务类型，为空时使用默认值
     * @return 新创建的钱包实体
     * @throws IllegalArgumentException 如果 customerNo 为空
     * @throws BusinessException 如果创建失败
     */
    Wallet createWallet(String customerNo, String bizType);

    /**
     * 查询用户钱包
     *
     * @param customerNo 用户编号，不能为空
     * @param bizType    业务类型，为空时使用默认值
     * @return 钱包实体，不存在时返回 null
     * @throws IllegalArgumentException 如果 customerNo 为空
     */
    Wallet getWallet(String customerNo, String bizType);

    /**
     * 获取或创建用户钱包
     *
     * <p>先查询，不存在则创建。并发安全。</p>
     *
     * @param customerNo 用户编号，不能为空
     * @param bizType    业务类型，为空时使用默认值
     * @return 钱包实体（必定非空）
     */
    Wallet getOrCreateWallet(String customerNo, String bizType);

    // ==================== 余额查询 ====================

    /**
     * 查询钱包余额详情
     *
     * @param customerNo 用户编号，不能为空
     * @param bizType    业务类型，为空时使用默认值
     * @return 钱包余额 DTO，包含可用余额、冻结余额、累计数据等
     * @throws IllegalArgumentException 如果 customerNo 为空
     */
    WalletBalanceDTO getWalletBalance(String customerNo, String bizType);

    // ==================== 交易历史 ====================

    /**
     * 查询用户交易历史
     *
     * @param customerNo      用户编号，不能为空
     * @param bizType         业务类型，为空时使用默认值
     * @param pageNum         页码，从1开始，null 时默认为1
     * @param pageSize        每页大小，最大100，null 时默认为20
     * @param transactionType 交易类型过滤，可选
     * @return 交易历史分页列表
     */
    Page<TransactionHistoryDTO> getTransactionHistory(String customerNo, String bizType,
                                                      Integer pageNum, Integer pageSize, String transactionType);

    /**
     * 管理端分页查询交易记录
     *
     * @param query    查询条件
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 交易记录分页列表
     */
    Page<TransactionHistoryDTO> queryTransactions(TransactionQueryDTO query, Integer pageNum, Integer pageSize);

    /**
     * 管理端分页查询钱包列表
     *
     * @param query    查询条件
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @return 钱包分页列表
     */
    Page<Wallet> queryWallets(WalletAdminQueryDTO query, Integer pageNum, Integer pageSize);

    /**
     * 内部查询：按客户编号分页查询原始交易记录
     *
     * @param customerNo      用户编号
     * @param transactionType 交易类型过滤，可选
     * @param pageNum         页码
     * @param pageSize        每页大小
     * @return 原始交易记录分页列表
     */
    Page<WalletTransaction> getTransactionsByCustomerNo(String customerNo, String transactionType,
                                                        Integer pageNum, Integer pageSize);

    /**
     * 查询用户收入统计
     *
     * @param customerNo 用户编号
     * @param startTime  开始时间（ISO格式），可选
     * @param endTime    结束时间（ISO格式），可选
     * @return 交易记录列表
     */
    List<WalletTransaction> getIncomeStatistics(String customerNo, String startTime, String endTime);
}
