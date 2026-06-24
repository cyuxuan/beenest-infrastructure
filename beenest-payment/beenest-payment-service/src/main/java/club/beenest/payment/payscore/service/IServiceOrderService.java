package club.beenest.payment.payscore.service;

import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 服务订单服务接口
 * 定义支付分信用免押的核心业务逻辑
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>信用免押检查 - 查询用户是否满足免押条件</li>
 *   <li>创建服务订单 - 发起授权请求，冻结信用额度</li>
 *   <li>处理授权回调 - 确认授权结果</li>
 *   <li>完结服务订单 - 扣取实际费用，解冻剩余额度</li>
 *   <li>取消服务订单 - 取消授权，解冻额度</li>
 * </ol>
 *
 * @author System
 * @since 2026-06-15
 */
public interface IServiceOrderService {

    /**
     * 信用免押检查
     * 查询用户是否满足信用免押条件
     *
     * @param customerNo 用户/商户编号
     * @param platform 支付分平台（WECHAT_PAYSCORE / ALIPAY_ZHIMA）
     * @param depositAmount 保证金金额（分）
     * @return 免押检查结果
     */
    CreditCheckResultDTO checkCreditEligibility(String customerNo, String platform, Long depositAmount);

    /**
     * 创建服务订单
     * 发起信用免押授权请求，冻结信用额度
     *
     * @param customerNo 用户/商户编号
     * @param request 创建服务订单请求
     * @return 服务订单结果（含授权跳转参数）
     */
    ServiceOrderResultDTO createServiceOrder(String customerNo, ServiceOrderCreateDTO request);

    /**
     * 处理授权回调
     * 验证签名并更新服务订单状态为已授权
     *
     * @param platform 支付分平台
     * @param request HTTP请求对象
     * @return true表示处理成功
     */
    boolean handleAuthCallback(String platform, HttpServletRequest request);

    /**
     * 完结服务订单
     * 扣取实际费用，解冻剩余额度
     *
     * @param orderNo 服务订单号
     * @param actualAmount 实际扣款金额（分，0表示全额解冻）
     * @return 服务订单结果
     */
    ServiceOrderResultDTO completeServiceOrder(String orderNo, Long actualAmount);

    /**
     * 处理完结回调
     * 验证签名并更新服务订单状态为已完结
     *
     * @param platform 支付分平台
     * @param request HTTP请求对象
     * @return true表示处理成功
     */
    boolean handleCompleteCallback(String platform, HttpServletRequest request);

    /**
     * 取消服务订单
     * 取消授权并全额解冻
     *
     * @param orderNo 服务订单号
     * @param reason 取消原因
     * @return 是否取消成功
     */
    boolean cancelServiceOrder(String orderNo, String reason);

    /**
     * 查询服务订单状态
     *
     * @param customerNo 用户/商户编号
     * @param orderNo 服务订单号
     * @return 服务订单结果
     */
    ServiceOrderResultDTO queryServiceOrderStatus(String customerNo, String orderNo);

    /**
     * 根据业务单号查询最新服务订单
     *
     * @param bizNo 业务单号
     * @return 服务订单结果
     */
    ServiceOrderResultDTO getLatestServiceOrderByBizNo(String bizNo);

    /**
     * 修改服务订单冻结金额
     *
     * @param orderNo 服务订单号
     * @param newAmount 新冻结金额（分）
     * @return 服务订单结果
     */
    ServiceOrderResultDTO modifyServiceOrderAmount(String orderNo, Long newAmount);
}
