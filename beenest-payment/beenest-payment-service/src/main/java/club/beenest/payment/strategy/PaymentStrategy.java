package club.beenest.payment.strategy;

import club.beenest.payment.object.entity.PaymentOrder;
import club.beenest.payment.object.entity.Refund;

import java.util.Map;

/**
 * 支付策略接口
 * 定义支付平台的通用接口
 * 
 * <p>使用策略模式，每个支付平台实现此接口</p>
 * 
 * <h3>设计模式：</h3>
 * <ul>
 *   <li>策略模式 - 定义支付算法族，让它们可以互相替换</li>
 *   <li>模板方法模式 - 定义支付流程骨架</li>
 * </ul>
 * 
 * <h3>扩展性：</h3>
 * <ul>
 *   <li>新增支付平台只需实现此接口</li>
 *   <li>不影响现有代码</li>
 *   <li>符合开闭原则</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
public interface PaymentStrategy {
    
    /**
     * 获取支付平台标识
     * 
     * @return 支付平台标识，如：WECHAT、ALIPAY、DOUYIN
     */
    String getPlatform();
    
    /**
     * 获取支付平台显示名称
     * 
     * @return 支付平台显示名称，如：微信支付、支付宝、抖音支付
     */
    String getPlatformName();
    
    /**
     * 检查支付平台是否启用
     * 
     * @return true表示启用，false表示未启用
     */
    boolean isEnabled();
    
    /**
     * 创建支付订单
     * 
     * <p>调用支付平台API创建支付订单，返回支付参数。</p>
     * 
     * <h4>实现要点：</h4>
     * <ul>
     *   <li>调用支付平台的创建订单API</li>
     *   <li>处理API返回结果</li>
     *   <li>生成前端所需的支付参数</li>
     *   <li>处理异常情况</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单对象，包含订单信息
     * @return 支付参数Map，包含前端调起支付所需的参数
     * @throws RuntimeException 如果创建订单失败
     */
    Map<String, Object> createPayment(PaymentOrder paymentOrder);
    
    /**
     * 验证支付回调签名
     * 
     * <p>验证支付平台回调数据的签名，确保数据来源可信。</p>
     * 
     * <h4>实现要点：</h4>
     * <ul>
     *   <li>按照平台规则提取签名</li>
     *   <li>按照平台规则计算签名</li>
     *   <li>比对签名是否一致</li>
     *   <li>验证证书（如需要）</li>
     * </ul>
     * 
     * @param callbackData 回调数据Map
     * @return true表示签名验证通过，false表示验证失败
     */
    boolean verifyCallback(Map<String, String> callbackData);
    
    /**
     * 解析回调数据
     * 
     * <p>从回调数据中提取关键信息。</p>
     * 
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>orderNo - 订单号</li>
     *   <li>transactionNo - 第三方交易号</li>
     *   <li>amount - 支付金额（分）</li>
     *   <li>status - 支付状态</li>
     * </ul>
     * 
     * @param callbackData 回调数据Map
     * @return 解析后的数据Map
     */
    Map<String, Object> parseCallback(Map<String, String> callbackData);
    
    /**
     * 查询支付订单状态
     * 
     * <p>主动查询支付平台的订单状态。</p>
     * 
     * <h4>实现要点：</h4>
     * <ul>
     *   <li>调用支付平台的查询API</li>
     *   <li>解析查询结果</li>
     *   <li>返回标准化的状态信息</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单对象
     * @return 订单状态信息Map
     * @throws RuntimeException 如果查询失败
     */
    Map<String, Object> queryPayment(PaymentOrder paymentOrder);
    
    /**
     * 取消支付订单
     * 
     * <p>取消支付平台的订单（如果支持）。</p>
     * 
     * <h4>实现要点：</h4>
     * <ul>
     *   <li>调用支付平台的取消API</li>
     *   <li>处理取消结果</li>
     *   <li>如果平台不支持取消，返回false</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单对象
     * @return true表示取消成功，false表示取消失败或不支持
     */
    boolean cancelPayment(PaymentOrder paymentOrder);
    
    /**
     * 申请退款
     * 
     * <p>向支付平台申请退款。</p>
     * 
     * <h4>实现要点：</h4>
     * <ul>
     *   <li>调用支付平台的退款API</li>
     *   <li>处理退款结果</li>
     *   <li>返回退款信息</li>
     * </ul>
     * 
     * @param paymentOrder 充值订单对象
     * @param refundAmount 退款金额（分）
     * @param refundReason 退款原因
     * @param refundNo 退款单号（用于幂等性）
     * @return 退款结果Map
     * @throws RuntimeException 如果退款失败
     */
    Map<String, Object> refund(PaymentOrder paymentOrder, Long refundAmount, String refundReason, String refundNo);

    /**
     * 查询退款状态
     *
     * <p>用于补偿异步退款结果，避免本地长时间停留在处理中。</p>
     *
     * @param paymentOrder 原支付订单
     * @param refund 退款单
     * @return 退款状态信息Map
     */
    Map<String, Object> queryRefund(PaymentOrder paymentOrder, Refund refund);

    /**
     * 验证退款回调签名
     *
     * @param callbackData 回调数据
     * @return 是否验证通过
     */
    default boolean verifyRefundCallback(Map<String, String> callbackData) {
        return false;
    }

    /**
     * 解析退款回调数据
     *
     * @param callbackData 回调数据
     * @return 解析后的标准结果
     */
    default Map<String, Object> parseRefundCallback(Map<String, String> callbackData) {
        throw new UnsupportedOperationException("支付平台暂不支持退款回调解析");
    }
    
    /**
     * 获取回调成功响应
     * 
     * <p>返回支付平台要求的成功响应格式。</p>
     * 
     * @return 成功响应字符串
     */
    String getSuccessResponse();
    
    /**
     * 获取回调失败响应
     * 
     * <p>返回支付平台要求的失败响应格式。</p>
     * 
     * @return 失败响应字符串
     */
    String getFailureResponse();
}
