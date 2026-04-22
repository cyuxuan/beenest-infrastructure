package club.beenest.payment.paymentorder.service;

import club.beenest.payment.paymentorder.dto.BatchSyncResultDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentResultDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundSyncResultDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import com.github.pagehelper.Page;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付服务接口
 * 定义支付相关的业务逻辑接口
 * 
 * <p>主要功能：</p>
 * <ul>
 *   <li>充值订单管理 - 创建、查询、更新充值订单</li>
 *   <li>支付集成 - 对接微信、支付宝、抖音等支付平台</li>
 *   <li>回调处理 - 处理支付平台的异步通知</li>
 *   <li>订单状态同步 - 同步支付状态到本地订单</li>
 * </ul>
 * 
 * <h3>支持平台：</h3>
 * <ul>
 *   <li>WECHAT - 微信支付（小程序支付）</li>
 *   <li>ALIPAY - 支付宝（小程序支付）</li>
 *   <li>DOUYIN - 抖音支付（小程序支付）</li>
 * </ul>
 * 
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>签名验证 - 验证支付平台回调签名</li>
 *   <li>重复处理防护 - 防止重复处理同一笔订单</li>
 *   <li>金额校验 - 校验支付金额与订单金额一致性</li>
 *   <li>状态控制 - 严格的订单状态流转控制</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
public interface IPaymentService {

    /**
     * 创建充值订单
     * 
     * <p>创建充值订单并调用对应支付平台的API生成支付参数。</p>
     * 
     * <h4>创建流程：</h4>
     * <ol>
     *   <li>验证用户和充值参数</li>
     *   <li>检查用户钱包状态</li>
     *   <li>生成唯一订单号</li>
     *   <li>创建充值订单记录</li>
     *   <li>调用支付平台API</li>
     *   <li>返回支付参数</li>
     * </ol>
     * 
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>orderNo - 订单号</li>
     *   <li>支付参数 - 根据平台不同返回不同格式的支付参数</li>
     *   <li>expireTime - 订单过期时间</li>
     *   <li>amount - 充值金额</li>
     * </ul>
     * 
     * <h4>微信支付返回参数：</h4>
     * <ul>
     *   <li>timeStamp - 时间戳</li>
     *   <li>nonceStr - 随机字符串</li>
     *   <li>package - 预支付交易会话标识</li>
     *   <li>signType - 签名类型</li>
     *   <li>paySign - 支付签名</li>
     * </ul>
     * 
     * <h4>支付宝返回参数：</h4>
     * <ul>
     *   <li>tradeNo - 支付宝交易号</li>
     *   <li>orderString - 订单信息字符串</li>
     * </ul>
     * 
     * <h4>抖音支付返回参数：</h4>
     * <ul>
     *   <li>orderId - 订单ID</li>
     *   <li>orderToken - 订单令牌</li>
     * </ul>
     * 
     * @param customerNo 用户编号，不能为空
     * @param rechargeRequest 充值请求参数，包含金额、平台等信息
     * @return 充值订单信息和支付参数
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果创建订单失败或调用支付API失败
     */
    OrderPaymentResultDTO createRechargeOrder(String customerNo, RechargeRequestDTO rechargeRequest);

    /**
     * 处理支付回调
     * 
     * <p>处理第三方支付平台的异步回调通知，更新订单状态并增加用户余额。</p>
     * 
     * <h4>处理流程：</h4>
     * <ol>
     *   <li>解析回调数据</li>
     *   <li>验证回调签名</li>
     *   <li>查询本地订单</li>
     *   <li>校验订单状态和金额</li>
     *   <li>更新订单状态</li>
     *   <li>增加用户钱包余额</li>
     *   <li>记录交易流水</li>
     *   <li>记录回调日志</li>
     * </ol>
     * 
     * <h4>安全验证：</h4>
     * <ul>
     *   <li>签名验证 - 验证回调数据的签名</li>
     *   <li>订单状态检查 - 只处理待支付状态的订单</li>
     *   <li>金额校验 - 校验支付金额与订单金额一致</li>
     *   <li>重复处理防护 - 防止重复处理同一笔订单</li>
     * </ul>
     * 
     * <h4>异常处理：</h4>
     * <ul>
     *   <li>签名验证失败 - 记录日志并返回失败</li>
     *   <li>订单不存在 - 记录日志并返回失败</li>
     *   <li>订单状态异常 - 记录日志并返回成功（避免重复通知）</li>
     *   <li>金额不匹配 - 记录日志并返回失败</li>
     * </ul>
     * 
     * @param platform 支付平台，WECHAT、ALIPAY、DOUYIN
     * @param request HTTP请求对象，包含回调数据
     * @return 处理结果，true表示处理成功，false表示处理失败
     * @throws RuntimeException 如果处理过程中发生系统异常
     */
    boolean handlePaymentCallback(String platform, HttpServletRequest request);

    /**
     * 处理退款回调
     *
     * @param platform 支付平台
     * @param request HTTP请求
     * @return 处理结果
     */
    boolean handleRefundCallback(String platform, HttpServletRequest request);

    /**
     * 查询订单支付状态
     * 
     * <p>主动查询第三方支付平台的订单状态，用于订单状态同步。</p>
     * 
     * <h4>使用场景：</h4>
     * <ul>
     *   <li>用户查询支付结果</li>
     *   <li>定时任务同步订单状态</li>
     *   <li>回调通知丢失时的补偿机制</li>
     * </ul>
     * 
     * <h4>查询流程：</h4>
     * <ol>
     *   <li>查询本地订单信息</li>
     *   <li>调用支付平台查询API</li>
     *   <li>解析查询结果</li>
     *   <li>更新本地订单状态</li>
     *   <li>如果支付成功，增加用户余额</li>
     * </ol>
     * 
     * @param orderNo 订单号，不能为空
     * @return 订单支付状态信息
     * @throws IllegalArgumentException 如果订单号为空
     * @throws RuntimeException 如果查询失败
     */
    PaymentStatusDTO queryPaymentStatus(String customerNo, String orderNo);

    /**
     * 管理端查询支付状态（不做用户归属校验）
     *
     * @param orderNo 订单号
     * @return 支付状态
     */
    PaymentStatusDTO queryPaymentStatusForAdmin(String orderNo);

    /**
     * 取消充值订单
     * 
     * <p>取消未支付的充值订单。</p>
     * 
     * <h4>取消条件：</h4>
     * <ul>
     *   <li>订单状态为PENDING（待支付）</li>
     *   <li>订单未过期</li>
     *   <li>用户有权限取消该订单</li>
     * </ul>
     * 
     * <h4>取消流程：</h4>
     * <ol>
     *   <li>验证订单状态</li>
     *   <li>检查用户权限</li>
     *   <li>更新订单状态为CANCELLED</li>
     *   <li>释放相关资源</li>
     * </ol>
     * 
     * @param customerNo 用户编号，不能为空
     * @param orderNo 订单号，不能为空
     * @return 取消结果，true表示取消成功，false表示取消失败
     * @throws IllegalArgumentException 如果参数无效
     * @throws RuntimeException 如果取消过程中发生异常
     */
    boolean cancelRechargeOrder(String customerNo, String orderNo);

    /**
     * 创建订单支付（直接支付模式）
     *
     * <p>为计划订单创建支付，调用支付平台API生成支付参数。</p>
     *
     * @param customerNo 用户编号
     * @param request 订单支付请求参数
     * @return 支付参数（orderNo, paymentParams, expireTime等）
     */
    OrderPaymentResultDTO createOrderPayment(String customerNo, OrderPaymentRequestDTO request);

    /**
     * 查询充值订单
     *
     * @param query 查询参数
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 订单分页列表
     */
    Page<PaymentOrder> queryOrders(PaymentOrderQueryDTO query, int pageNum, int pageSize);

    /**
     * 申请退款
     * 
     * @param orderNo 订单号
     * @param amount 退款金额
     * @param reason 退款原因
     */
    Refund applyRefund(String orderNo, Long amount, String reason);

    /**
     * 创建待审核退款申请，不立即调用第三方退款。
     *
     * @param orderNo 订单号
     * @param amount 退款金额
     * @param reason 退款原因
     */
    Refund requestRefundReview(String orderNo, Long amount, String reason);

    /**
     * 查询退款记录
     * 
     * @param query 查询条件
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 退款记录分页
     */
    Page<Refund> queryRefunds(RefundQueryDTO query, int pageNum, int pageSize);

    /**
     * 主动同步退款状态
     *
     * @param refundNo 退款单号
     * @return 同步后的退款状态
     */
    RefundSyncResultDTO syncRefundStatus(String refundNo);

    /**
     * 批量同步处理中退款状态
     *
     * @param limit 本次处理数量上限
     * @return 同步结果
     */
    BatchSyncResultDTO syncProcessingRefunds(int limit);

    /**
     * 审核退款
     * 
     * @param id 退款ID
     * @param status 状态 (SUCCESS/FAILED)
     * @param remark 备注
     */
    void auditRefund(Long id, String status, String remark);

    /**
     * 根据 bizNo 查询最新的支付订单（供 drone-system 查询）
     */
    PaymentOrder getLatestPaymentOrderByBizNo(String bizNo);

    /**
     * 根据订单号查询退款列表（供 drone-system 查询）
     */
    java.util.List<Refund> getRefundsByOrderNo(String orderNo);

    /**
     * 根据订单号查询最新待处理的退款（供 drone-system 查询）
     */
    Refund getLatestPendingRefundByOrderNo(String orderNo);

    /**
     * 按时间范围和平台查询已支付订单（供对账使用）
     *
     * @param start    开始时间
     * @param end      结束时间
     * @param platform 支付平台
     * @return 已支付订单列表
     */
    List<PaymentOrder> getPaidOrdersByTimeRange(LocalDateTime start, LocalDateTime end, String platform);
}
