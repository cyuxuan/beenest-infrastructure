package club.beenest.payment.withdraw.strategy;

import club.beenest.payment.withdraw.domain.entity.WithdrawRequest;

import java.util.Map;

/**
 * 提现策略接口
 * 定义提现平台的通用接口
 * 
 * <p>每个提现平台（微信、支付宝、抖音等）都需要实现此接口。</p>
 * 
 * <h3>设计模式：</h3>
 * <ul>
 *   <li>策略模式 - 定义提现算法族，让它们可以互相替换</li>
 *   <li>工厂模式 - 通过工厂获取具体的提现策略</li>
 *   <li>模板方法模式 - 在抽象类中定义提现流程骨架</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-27
 */
public interface WithdrawStrategy {
    
    /**
     * 获取平台标识
     * 
     * @return 平台标识（WECHAT、ALIPAY、DOUYIN等）
     */
    String getPlatform();
    
    /**
     * 获取平台显示名称
     * 
     * @return 平台显示名称（微信零钱、支付宝、抖音等）
     */
    String getPlatformName();
    
    /**
     * 检查平台是否启用
     * 
     * @return true表示启用，false表示禁用
     */
    boolean isEnabled();
    
    /**
     * 验证提现账户信息
     * 
     * <p>验证账户信息格式是否正确。</p>
     * 
     * @param accountInfo 账户信息（openid、支付宝账号、手机号等）
     * @param accountName 账户名称（真实姓名）
     * @return 验证结果，true表示验证通过，false表示验证失败
     */
    boolean validateAccount(String accountInfo, String accountName);
    
    /**
     * 执行提现
     * 
     * <p>调用第三方平台API执行提现操作。</p>
     * 
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>success - 是否成功</li>
     *   <li>transactionNo - 第三方交易号</li>
     *   <li>message - 处理消息</li>
     *   <li>processTime - 处理时间</li>
     * </ul>
     * 
     * @param withdrawRequest 提现申请
     * @return 提现结果
     * @throws Exception 如果提现失败
     */
    Map<String, Object> executeWithdraw(WithdrawRequest withdrawRequest) throws Exception;
    
    /**
     * 查询提现状态
     * 
     * <p>查询第三方平台的提现状态。</p>
     * 
     * @param withdrawRequest 提现申请
     * @return 提现状态信息
     * @throws Exception 如果查询失败
     */
    Map<String, Object> queryWithdrawStatus(WithdrawRequest withdrawRequest) throws Exception;
    
    /**
     * 计算手续费
     * 
     * <p>根据提现金额计算手续费。</p>
     * 
     * @param amount 提现金额（分）
     * @return 手续费金额（分）
     */
    Long calculateFee(Long amount);
    
    /**
     * 获取最小提现金额
     * 
     * @return 最小提现金额（分）
     */
    Long getMinAmount();
    
    /**
     * 获取最大提现金额
     * 
     * @return 最大提现金额（分）
     */
    Long getMaxAmount();
}
