package club.beenest.payment.payscore.strategy;

import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;

import java.util.Map;

/**
 * 支付分策略接口
 * 定义信用免押（微信支付分 / 支付宝芝麻信用）的通用接口
 *
 * <p>支付分与普通支付的核心区别：</p>
 * <ul>
 *   <li>普通支付：下单 → 支付 → 回调确认（即时交易）</li>
 *   <li>支付分：创建授权 → 用户确认 → 冻结额度 → 服务中 → 完结扣款 → 解冻（信用免押）</li>
 * </ul>
 *
 * <h3>设计模式：</h3>
 * <ul>
 *   <li>策略模式 - 每个支付分平台作为独立策略</li>
 *   <li>模板方法模式 - 定义信用免押流程骨架</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
public interface PayScoreStrategy {

    /**
     * 获取支付分平台标识
     *
     * @return 平台标识，如：WECHAT_PAYSCORE、ALIPAY_ZHIMA
     */
    String getPlatform();

    /**
     * 获取支付分平台显示名称
     *
     * @return 平台显示名称，如：微信支付分、支付宝芝麻信用
     */
    String getPlatformName();

    /**
     * 检查支付分平台是否启用
     *
     * @return true表示启用，false表示未启用
     */
    boolean isEnabled();

    /**
     * 信用免押检查
     * 查询用户是否满足信用免押条件，以及可免押的金额
     *
     * @param userId 用户/商户编号
     * @param serviceId 支付分服务ID
     * @param depositAmount 保证金金额（分）
     * @return 免押检查结果
     */
    CreditCheckResultDTO checkCreditEligibility(String userId, String serviceId, long depositAmount);

    /**
     * 创建服务订单
     * 发起授权请求，用户确认后冻结信用额度
     *
     * <p>返回前端跳转授权页面所需的参数。</p>
     *
     * @param serviceOrder 服务订单实体
     * @return 授权跳转参数Map
     */
    Map<String, Object> createServiceOrder(ServiceOrder serviceOrder);

    /**
     * 查询服务订单状态
     * 主动查询第三方支付分平台的服务订单状态
     *
     * @param serviceOrder 服务订单实体
     * @return 订单状态信息Map
     */
    Map<String, Object> queryServiceOrder(ServiceOrder serviceOrder);

    /**
     * 完结服务订单
     * 扣取实际费用，解冻剩余额度
     *
     * @param serviceOrder 服务订单实体
     * @param actualAmount 实际扣款金额（分，0表示全额解冻）
     * @return 完结结果Map
     */
    Map<String, Object> completeServiceOrder(ServiceOrder serviceOrder, long actualAmount);

    /**
     * 取消服务订单
     * 取消授权并全额解冻
     *
     * @param serviceOrder 服务订单实体
     * @param reason 取消原因
     * @return 取消结果Map
     */
    Map<String, Object> cancelServiceOrder(ServiceOrder serviceOrder, String reason);

    /**
     * 修改服务订单金额
     * 服务过程中调整冻结额度
     *
     * @param serviceOrder 服务订单实体
     * @param newAmount 新的冻结金额（分）
     * @return 修改结果Map
     */
    Map<String, Object> modifyServiceOrder(ServiceOrder serviceOrder, long newAmount);

    /**
     * 验证授权回调签名
     *
     * @param callbackData 回调数据
     * @return true表示验证通过
     */
    boolean verifyAuthCallback(Map<String, String> callbackData);

    /**
     * 解析授权回调数据
     * 从回调数据中提取授权结果信息
     *
     * @param callbackData 回调数据
     * @return 解析后的数据Map（orderNo, thirdPartyOrderNo, frozenAmount等）
     */
    Map<String, Object> parseAuthCallback(Map<String, String> callbackData);

    /**
     * 验证完结回调签名
     *
     * @param callbackData 回调数据
     * @return true表示验证通过
     */
    boolean verifyCompleteCallback(Map<String, String> callbackData);

    /**
     * 解析完结回调数据
     * 从回调数据中提取完结结果信息
     *
     * @param callbackData 回调数据
     * @return 解析后的数据Map（orderNo, actualAmount, status等）
     */
    Map<String, Object> parseCompleteCallback(Map<String, String> callbackData);

    /**
     * 获取回调成功响应
     *
     * @return 成功响应字符串
     */
    String getSuccessResponse();

    /**
     * 获取回调失败响应
     *
     * @return 失败响应字符串
     */
    String getFailureResponse();
}
