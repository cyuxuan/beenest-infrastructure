package club.beenest.payment.strategy;

import club.beenest.payment.config.WithdrawConfig;
import club.beenest.payment.object.entity.WithdrawRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 抽象提现策略
 * 实现提现流程的模板方法
 * 
 * <p>定义提现流程的骨架，具体步骤由子类实现。</p>
 * 
 * <h3>模板方法模式：</h3>
 * <ol>
 *   <li>验证账户信息</li>
 *   <li>调用第三方API执行提现</li>
 *   <li>处理提现结果</li>
 * </ol>
 * 
 * @author System
 * @since 2026-01-27
 */
@Slf4j
public abstract class AbstractWithdrawStrategy implements WithdrawStrategy {
    
    protected final WithdrawConfig withdrawConfig;
    
    protected AbstractWithdrawStrategy(WithdrawConfig withdrawConfig) {
        this.withdrawConfig = withdrawConfig;
    }
    
    @Override
    public boolean validateAccount(String accountInfo, String accountName) {
        // 基本验证
        if (!StringUtils.hasText(accountInfo)) {
            log.warn("账户信息为空 - platform: {}", getPlatform());
            return false;
        }
        
        if (!StringUtils.hasText(accountName)) {
            log.warn("账户名称为空 - platform: {}", getPlatform());
            return false;
        }
        
        // 调用子类的具体验证逻辑
        return doValidateAccount(accountInfo, accountName);
    }
    
    @Override
    public Map<String, Object> executeWithdraw(WithdrawRequest withdrawRequest) throws Exception {
        log.info("执行提现 - platform: {}, requestNo: {}, amount: {}", 
                getPlatform(), withdrawRequest.getRequestNo(), withdrawRequest.getAmount());
        
        try {
            // 1. 验证账户信息
            if (!validateAccount(withdrawRequest.getAccountInfo(), withdrawRequest.getAccountName())) {
                throw new IllegalArgumentException("账户信息验证失败");
            }
            
            // 2. 执行提现
            Map<String, Object> result = doExecuteWithdraw(withdrawRequest);
            
            log.info("提现执行成功 - platform: {}, requestNo: {}", 
                    getPlatform(), withdrawRequest.getRequestNo());
            
            return result;
            
        } catch (Exception e) {
            log.error("提现执行失败 - platform: {}, requestNo: {}, error: {}", 
                    getPlatform(), withdrawRequest.getRequestNo(), e.getMessage(), e);
            throw e;
        }
    }
    
    @Override
    public Map<String, Object> queryWithdrawStatus(WithdrawRequest withdrawRequest) throws Exception {
        log.info("查询提现状态 - platform: {}, requestNo: {}", 
                getPlatform(), withdrawRequest.getRequestNo());
        
        try {
            Map<String, Object> result = doQueryWithdrawStatus(withdrawRequest);
            
            log.info("查询提现状态成功 - platform: {}, requestNo: {}, status: {}", 
                    getPlatform(), withdrawRequest.getRequestNo(), result.get("status"));
            
            return result;
            
        } catch (Exception e) {
            log.error("查询提现状态失败 - platform: {}, requestNo: {}, error: {}", 
                    getPlatform(), withdrawRequest.getRequestNo(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 验证账户信息（子类实现）
     * 
     * @param accountInfo 账户信息
     * @param accountName 账户名称
     * @return 验证结果
     */
    protected abstract boolean doValidateAccount(String accountInfo, String accountName);
    
    /**
     * 执行提现（子类实现）
     * 
     * @param withdrawRequest 提现申请
     * @return 提现结果
     * @throws Exception 如果提现失败
     */
    protected abstract Map<String, Object> doExecuteWithdraw(WithdrawRequest withdrawRequest) throws Exception;
    
    /**
     * 查询提现状态（子类实现）
     * 
     * @param withdrawRequest 提现申请
     * @return 提现状态
     * @throws Exception 如果查询失败
     */
    protected abstract Map<String, Object> doQueryWithdrawStatus(WithdrawRequest withdrawRequest) throws Exception;
    
    /**
     * 创建成功结果
     */
    protected Map<String, Object> createSuccessResult(String transactionNo, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("transactionNo", transactionNo);
        result.put("message", message);
        result.put("processTime", System.currentTimeMillis());
        return result;
    }
    
    /**
     * 创建失败结果
     */
    protected Map<String, Object> createFailureResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message);
        result.put("processTime", System.currentTimeMillis());
        return result;
    }
}
