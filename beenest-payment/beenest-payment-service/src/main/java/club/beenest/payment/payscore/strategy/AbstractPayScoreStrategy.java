package club.beenest.payment.payscore.strategy;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.paymentorder.config.PaymentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 抽象支付分策略
 * 实现信用免押流程的通用逻辑，使用模板方法模式定义流程骨架
 *
 * <h3>扩展点（由子类实现）：</h3>
 * <ul>
 *   <li>doCheckCreditEligibility() - 信用免押检查的具体实现</li>
 *   <li>doCreateServiceOrder() - 创建服务订单的具体实现</li>
 *   <li>doQueryServiceOrder() - 查询服务订单的具体实现</li>
 *   <li>doCompleteServiceOrder() - 完结服务订单的具体实现</li>
 *   <li>doCancelServiceOrder() - 取消服务订单的具体实现</li>
 *   <li>doModifyServiceOrder() - 修改服务订单金额的具体实现</li>
 *   <li>doVerifyAuthCallback() - 验证授权回调签名的具体实现</li>
 *   <li>doParseAuthCallback() - 解析授权回调数据的具体实现</li>
 *   <li>doVerifyCompleteCallback() - 验证完结回调签名的具体实现</li>
 *   <li>doParseCompleteCallback() - 解析完结回调数据的具体实现</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Slf4j
public abstract class AbstractPayScoreStrategy implements PayScoreStrategy {

    protected final PaymentConfig paymentConfig;

    protected AbstractPayScoreStrategy(PaymentConfig paymentConfig) {
        this.paymentConfig = paymentConfig;
    }

    // ==================== 模板方法（定义流程骨架） ====================

    /**
     * 信用免押检查（模板方法）
     */
    @Override
    public final CreditCheckResultDTO checkCreditEligibility(String userId, String serviceId, long depositAmount) {
        log.info("信用免押检查 - platform: {}, userId: {}, depositAmount: {}", getPlatform(), userId, depositAmount);
        try {
            validateCreditCheckParams(userId, serviceId, depositAmount);
            CreditCheckResultDTO result = doCheckCreditEligibility(userId, serviceId, depositAmount);
            log.info("信用免押检查完成 - platform: {}, userId: {}, eligible: {}, exemptionResult: {}",
                    getPlatform(), userId, result.isEligible(), result.getExemptionResult());
            return result;
        } catch (Exception e) {
            log.error("信用免押检查失败 - platform: {}, userId: {}, error: {}", getPlatform(), userId, e.getMessage(), e);
            throw new BusinessException("信用免押检查失败：" + e.getMessage(), e);
        }
    }

    /**
     * 创建服务订单（模板方法）
     */
    @Override
    public final Map<String, Object> createServiceOrder(ServiceOrder serviceOrder) {
        log.info("创建服务订单 - platform: {}, orderNo: {}, depositAmount: {}",
                getPlatform(), serviceOrder.getOrderNo(), serviceOrder.getDepositAmount());
        try {
            validateServiceOrder(serviceOrder);
            Map<String, Object> authParams = doCreateServiceOrder(serviceOrder);
            if (authParams == null || authParams.isEmpty()) {
                throw new IllegalArgumentException("授权跳转参数为空");
            }
            log.info("创建服务订单成功 - platform: {}, orderNo: {}", getPlatform(), serviceOrder.getOrderNo());
            return authParams;
        } catch (Exception e) {
            log.error("创建服务订单失败 - platform: {}, orderNo: {}, error: {}",
                    getPlatform(), serviceOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("创建服务订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 查询服务订单（模板方法）
     */
    @Override
    public final Map<String, Object> queryServiceOrder(ServiceOrder serviceOrder) {
        log.info("查询服务订单 - platform: {}, orderNo: {}", getPlatform(), serviceOrder.getOrderNo());
        try {
            validateServiceOrder(serviceOrder);
            return doQueryServiceOrder(serviceOrder);
        } catch (Exception e) {
            log.error("查询服务订单失败 - platform: {}, orderNo: {}, error: {}",
                    getPlatform(), serviceOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("查询服务订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 完结服务订单（模板方法）
     */
    @Override
    public final Map<String, Object> completeServiceOrder(ServiceOrder serviceOrder, long actualAmount) {
        log.info("完结服务订单 - platform: {}, orderNo: {}, actualAmount: {}",
                getPlatform(), serviceOrder.getOrderNo(), actualAmount);
        try {
            validateServiceOrder(serviceOrder);
            // 完结金额校验：实际扣款不能超过冻结金额
            if (actualAmount < 0) {
                throw new IllegalArgumentException("实际扣款金额不能为负数");
            }
            if (serviceOrder.getFrozenAmount() != null && actualAmount > serviceOrder.getFrozenAmount()) {
                throw new IllegalArgumentException("实际扣款金额不能超过冻结金额");
            }
            return doCompleteServiceOrder(serviceOrder, actualAmount);
        } catch (Exception e) {
            log.error("完结服务订单失败 - platform: {}, orderNo: {}, error: {}",
                    getPlatform(), serviceOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("完结服务订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 取消服务订单（模板方法）
     */
    @Override
    public final Map<String, Object> cancelServiceOrder(ServiceOrder serviceOrder, String reason) {
        log.info("取消服务订单 - platform: {}, orderNo: {}, reason: {}",
                getPlatform(), serviceOrder.getOrderNo(), reason);
        try {
            validateServiceOrder(serviceOrder);
            return doCancelServiceOrder(serviceOrder, reason);
        } catch (Exception e) {
            log.error("取消服务订单失败 - platform: {}, orderNo: {}, error: {}",
                    getPlatform(), serviceOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("取消服务订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 修改服务订单金额（模板方法）
     */
    @Override
    public final Map<String, Object> modifyServiceOrder(ServiceOrder serviceOrder, long newAmount) {
        log.info("修改服务订单金额 - platform: {}, orderNo: {}, newAmount: {}",
                getPlatform(), serviceOrder.getOrderNo(), newAmount);
        try {
            validateServiceOrder(serviceOrder);
            if (newAmount <= 0) {
                throw new IllegalArgumentException("冻结金额必须大于0");
            }
            return doModifyServiceOrder(serviceOrder, newAmount);
        } catch (Exception e) {
            log.error("修改服务订单金额失败 - platform: {}, orderNo: {}, error: {}",
                    getPlatform(), serviceOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("修改服务订单金额失败：" + e.getMessage(), e);
        }
    }

    /**
     * 验证授权回调签名（模板方法）
     */
    @Override
    public final boolean verifyAuthCallback(Map<String, String> callbackData) {
        log.info("验证授权回调签名 - platform: {}", getPlatform());
        try {
            if (callbackData == null || callbackData.isEmpty()) {
                log.warn("授权回调数据为空 - platform: {}", getPlatform());
                return false;
            }
            return doVerifyAuthCallback(callbackData);
        } catch (Exception e) {
            log.error("验证授权回调签名异常 - platform: {}, error: {}", getPlatform(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析授权回调数据（模板方法）
     */
    @Override
    public final Map<String, Object> parseAuthCallback(Map<String, String> callbackData) {
        log.info("解析授权回调数据 - platform: {}", getPlatform());
        try {
            if (callbackData == null || callbackData.isEmpty()) {
                throw new IllegalArgumentException("授权回调数据为空");
            }
            return doParseAuthCallback(callbackData);
        } catch (Exception e) {
            log.error("解析授权回调数据失败 - platform: {}, error: {}", getPlatform(), e.getMessage(), e);
            throw new BusinessException("解析授权回调数据失败：" + e.getMessage(), e);
        }
    }

    /**
     * 验证完结回调签名（模板方法）
     */
    @Override
    public final boolean verifyCompleteCallback(Map<String, String> callbackData) {
        log.info("验证完结回调签名 - platform: {}", getPlatform());
        try {
            if (callbackData == null || callbackData.isEmpty()) {
                log.warn("完结回调数据为空 - platform: {}", getPlatform());
                return false;
            }
            return doVerifyCompleteCallback(callbackData);
        } catch (Exception e) {
            log.error("验证完结回调签名异常 - platform: {}, error: {}", getPlatform(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析完结回调数据（模板方法）
     */
    @Override
    public final Map<String, Object> parseCompleteCallback(Map<String, String> callbackData) {
        log.info("解析完结回调数据 - platform: {}", getPlatform());
        try {
            if (callbackData == null || callbackData.isEmpty()) {
                throw new IllegalArgumentException("完结回调数据为空");
            }
            return doParseCompleteCallback(callbackData);
        } catch (Exception e) {
            log.error("解析完结回调数据失败 - platform: {}, error: {}", getPlatform(), e.getMessage(), e);
            throw new BusinessException("解析完结回调数据失败：" + e.getMessage(), e);
        }
    }

    // ==================== 抽象方法（由子类实现） ====================

    protected abstract CreditCheckResultDTO doCheckCreditEligibility(String userId, String serviceId, long depositAmount) throws Exception;
    protected abstract Map<String, Object> doCreateServiceOrder(ServiceOrder serviceOrder) throws Exception;
    protected abstract Map<String, Object> doQueryServiceOrder(ServiceOrder serviceOrder) throws Exception;
    protected abstract Map<String, Object> doCompleteServiceOrder(ServiceOrder serviceOrder, long actualAmount) throws Exception;
    protected abstract Map<String, Object> doCancelServiceOrder(ServiceOrder serviceOrder, String reason) throws Exception;
    protected abstract Map<String, Object> doModifyServiceOrder(ServiceOrder serviceOrder, long newAmount) throws Exception;
    protected abstract boolean doVerifyAuthCallback(Map<String, String> callbackData) throws Exception;
    protected abstract Map<String, Object> doParseAuthCallback(Map<String, String> callbackData) throws Exception;
    protected abstract boolean doVerifyCompleteCallback(Map<String, String> callbackData) throws Exception;
    protected abstract Map<String, Object> doParseCompleteCallback(Map<String, String> callbackData) throws Exception;

    // ==================== 工具方法 ====================

    /**
     * 验证信用检查参数
     */
    protected void validateCreditCheckParams(String userId, String serviceId, long depositAmount) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户编号不能为空");
        }
        if (!StringUtils.hasText(serviceId)) {
            throw new IllegalArgumentException("服务ID不能为空");
        }
        if (depositAmount <= 0) {
            throw new IllegalArgumentException("保证金金额必须大于0");
        }
    }

    /**
     * 验证服务订单
     */
    protected void validateServiceOrder(ServiceOrder serviceOrder) {
        if (serviceOrder == null) {
            throw new IllegalArgumentException("服务订单不能为空");
        }
        if (!StringUtils.hasText(serviceOrder.getOrderNo())) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (!getPlatform().equals(serviceOrder.getPlatform())) {
            throw new IllegalArgumentException("支付分平台不匹配");
        }
    }

    /**
     * 构建通用成功响应
     */
    protected Map<String, Object> buildSuccessResponse(Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /**
     * 构建通用错误响应
     */
    protected Map<String, Object> buildErrorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
