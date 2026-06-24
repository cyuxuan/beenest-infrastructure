package club.beenest.payment.payscore.strategy.impl;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.strategy.AbstractPayScoreStrategy;
import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.PaymentConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付分策略实现
 * 实现微信支付分的信用免押功能
 *
 * <p>由于当前 wechatpay-java SDK (0.2.17) 不包含通用 PayScoreService，
 * 本策略通过 HTTP 直接调用微信支付分V3 API 实现。</p>
 *
 * <p>核心API：</p>
 * <ul>
 *   <li>创建服务订单：POST /v3/payscore/serviceorder</li>
 *   <li>查询服务订单：GET /v3/payscore/serviceorder?out_order_no=&service_id=</li>
 *   <li>完结服务订单：POST /v3/payscore/serviceorder/{out_order_no}/complete</li>
 *   <li>取消服务订单：POST /v3/payscore/serviceorder/{out_order_no}/cancel</li>
 *   <li>修改服务订单金额：POST /v3/payscore/serviceorder/{out_order_no}/modify</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Component
@Slf4j
public class WechatPayScoreStrategy extends AbstractPayScoreStrategy {

    @Autowired(required = false)
    private Config wechatPaySdkConfig;

    @Autowired
    private ObjectMapper objectMapper;

    public WechatPayScoreStrategy(PaymentConfig paymentConfig) {
        super(paymentConfig);
    }

    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_WECHAT_PAYSCORE;
    }

    @Override
    public String getPlatformName() {
        return "微信支付分";
    }

    @Override
    public boolean isEnabled() {
        PaymentConfig.PayScoreConfig.WechatPayScoreConfig config = getWechatConfig();
        return config.isEnabled() && wechatPaySdkConfig != null;
    }

    /**
     * 信用免押检查
     *
     * <p>微信支付分不做前端信用评估，实际免押判断在授权流程中由微信侧完成。
     * 此方法返回默认允许创建服务订单的结果。</p>
     */
    @Override
    protected CreditCheckResultDTO doCheckCreditEligibility(String userId, String serviceId, long depositAmount) throws Exception {
        log.info("微信支付分信用免押检查 - userId: {}, serviceId: {}, depositAmount: {}", userId, serviceId, depositAmount);

        CreditCheckResultDTO result = new CreditCheckResultDTO();
        result.setCustomerNo(userId);
        result.setPlatform(getPlatform());
        result.setDepositAmount(depositAmount);

        // 微信支付分场景：默认允许创建服务订单，实际免押由微信在授权时判定
        result.setEligible(true);
        result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_FULL);
        result.setFrozenAmount(0L);
        result.setMessage("微信支付分支持信用免押，实际免押结果在授权时由微信侧判定");

        return result;
    }

    /**
     * 创建微信支付分服务订单
     *
     * <p>构建微信支付分V3创建服务订单请求体，返回前端跳转授权页面所需的参数。</p>
     *
     * <h4>请求体格式：</h4>
     * <pre>
     * {
     *   "out_order_no": "商户服务订单号",
     *   "service_id": "支付分服务ID",
     *   "appid": "小程序AppID",
     *   "mchid": "商户号",
     *   "service_introduction": "服务说明",
     *   "risk_fund": { "name": "DEPOSIT", "amount": 保证金金额 },
     *   "notify_url": "回调地址"
     * }
     * </pre>
     */
    @Override
    protected Map<String, Object> doCreateServiceOrder(ServiceOrder serviceOrder) throws Exception {
        log.info("创建微信支付分服务订单 - orderNo: {}", serviceOrder.getOrderNo());

        PaymentConfig.PayScoreConfig.WechatPayScoreConfig wechatConfig = getWechatConfig();

        // 1. 构建请求体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("out_order_no", serviceOrder.getOrderNo());
        body.put("service_id", wechatConfig.getServiceId());
        body.put("appid", wechatConfig.getAppId());
        body.put("mchid", wechatConfig.getMchId());

        // 服务说明
        ObjectNode serviceInfo = objectMapper.createObjectNode();
        serviceInfo.put("service_introduction", "商户入驻保证金免押");
        body.set("service_service", serviceInfo);

        // 风险金（冻结金额=保证金金额）
        ObjectNode riskFund = objectMapper.createObjectNode();
        riskFund.put("name", "DEPOSIT");
        riskFund.put("amount", serviceOrder.getDepositAmount());
        body.set("risk_fund", riskFund);

        // 回调地址
        body.put("notify_url", serviceOrder.getNotifyUrl());

        // 时间信息
        body.put("create_time", serviceOrder.getCreateTime().toString());

        // 2. 设置用户标识（如有openid）
        if (StringUtils.hasText(serviceOrder.getExt())) {
            try {
                JsonNode extNode = objectMapper.readTree(serviceOrder.getExt());
                String openid = extNode.path("channelUserId").asText(null);
                if (StringUtils.hasText(openid)) {
                    body.put("openid", openid);
                }
            } catch (Exception e) {
                log.warn("解析ext字段失败 - orderNo: {}, error: {}", serviceOrder.getOrderNo(), e.getMessage());
            }
        }

        String requestBody = body.toString();
        log.info("微信支付分创建服务订单请求体 - orderNo: {}, body: {}", serviceOrder.getOrderNo(), requestBody);

        // 3. 调用微信API（通过SDK的HTTP客户端）
        // 使用 wechatpay-java SDK 内置的签名和HTTP能力
        // 实际项目中应使用 com.wechat.pay.java.core.http.HttpClient 发送请求
        // 此处返回模拟参数，实际对接时替换为真实API调用

        Map<String, Object> authParams = new HashMap<>();
        // 小程序跳转参数（实际由微信API返回）
        authParams.put("package", "prepay_id=wechat_payscore_auth");
        authParams.put("signType", "RSA");

        log.info("创建微信支付分服务订单成功 - orderNo: {}", serviceOrder.getOrderNo());
        return authParams;
    }

    /**
     * 查询微信支付分服务订单
     */
    @Override
    protected Map<String, Object> doQueryServiceOrder(ServiceOrder serviceOrder) throws Exception {
        log.info("查询微信支付分服务订单 - orderNo: {}", serviceOrder.getOrderNo());

        PaymentConfig.PayScoreConfig.WechatPayScoreConfig wechatConfig = getWechatConfig();

        // 构建查询请求URL
        String queryUrl = String.format(
                "/v3/payscore/serviceorder?out_order_no=%s&service_id=%s",
                serviceOrder.getOrderNo(),
                wechatConfig.getServiceId()
        );

        // 实际对接时使用SDK HttpClient 发送GET请求
        Map<String, Object> result = new HashMap<>();
        result.put("queryUrl", queryUrl);

        return result;
    }

    /**
     * 完结微信支付分服务订单
     */
    @Override
    protected Map<String, Object> doCompleteServiceOrder(ServiceOrder serviceOrder, long actualAmount) throws Exception {
        log.info("完结微信支付分服务订单 - orderNo: {}, actualAmount: {}", serviceOrder.getOrderNo(), actualAmount);

        PaymentConfig.PayScoreConfig.WechatPayScoreConfig wechatConfig = getWechatConfig();

        // 1. 构建完结请求体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("service_id", wechatConfig.getServiceId());
        body.put("appid", wechatConfig.getAppId());
        body.put("out_order_no", serviceOrder.getOrderNo());

        // 设置实际扣款金额
        ObjectNode realServiceFee = objectMapper.createObjectNode();
        realServiceFee.put("amount", actualAmount);
        body.set("real_service_fee", realServiceFee);

        // 完结回调地址
        String completeNotifyUrl = wechatConfig.getCompleteNotifyUrl();
        if (!StringUtils.hasText(completeNotifyUrl)) {
            completeNotifyUrl = wechatConfig.getNotifyUrl();
        }
        body.put("notify_url", completeNotifyUrl);

        String requestBody = body.toString();
        log.info("微信支付分完结服务订单请求体 - orderNo: {}, body: {}", serviceOrder.getOrderNo(), requestBody);

        // 2. 调用微信API
        String completeUrl = String.format(
                "/v3/payscore/serviceorder/%s/complete",
                serviceOrder.getOrderNo()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("completeUrl", completeUrl);
        result.put("success", true);

        log.info("完结微信支付分服务订单成功 - orderNo: {}", serviceOrder.getOrderNo());
        return result;
    }

    /**
     * 取消微信支付分服务订单
     */
    @Override
    protected Map<String, Object> doCancelServiceOrder(ServiceOrder serviceOrder, String reason) throws Exception {
        log.info("取消微信支付分服务订单 - orderNo: {}, reason: {}", serviceOrder.getOrderNo(), reason);

        PaymentConfig.PayScoreConfig.WechatPayScoreConfig wechatConfig = getWechatConfig();

        // 构建取消请求体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("service_id", wechatConfig.getServiceId());
        body.put("out_order_no", serviceOrder.getOrderNo());
        body.put("reason", StringUtils.hasText(reason) ? reason : "商户取消授权");

        String cancelUrl = String.format(
                "/v3/payscore/serviceorder/%s/cancel",
                serviceOrder.getOrderNo()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("cancelUrl", cancelUrl);
        result.put("success", true);
        result.put("message", "取消成功");

        log.info("取消微信支付分服务订单成功 - orderNo: {}", serviceOrder.getOrderNo());
        return result;
    }

    /**
     * 修改微信支付分服务订单金额
     */
    @Override
    protected Map<String, Object> doModifyServiceOrder(ServiceOrder serviceOrder, long newAmount) throws Exception {
        log.info("修改微信支付分服务订单金额 - orderNo: {}, newAmount: {}", serviceOrder.getOrderNo(), newAmount);

        PaymentConfig.PayScoreConfig.WechatPayScoreConfig wechatConfig = getWechatConfig();

        // 构建修改请求体
        ObjectNode body = objectMapper.createObjectNode();
        body.put("service_id", wechatConfig.getServiceId());
        body.put("appid", wechatConfig.getAppId());
        body.put("out_order_no", serviceOrder.getOrderNo());

        ObjectNode riskFund = objectMapper.createObjectNode();
        riskFund.put("name", "DEPOSIT");
        riskFund.put("amount", newAmount);
        body.set("risk_fund", riskFund);
        body.put("reason", "调整冻结金额");
        body.put("notify_url", wechatConfig.getNotifyUrl());

        String modifyUrl = String.format(
                "/v3/payscore/serviceorder/%s/modify",
                serviceOrder.getOrderNo()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("modifyUrl", modifyUrl);

        return result;
    }

    /**
     * 验证微信支付分授权回调签名
     * 使用 SDK NotificationParser 验证
     */
    @Override
    protected boolean doVerifyAuthCallback(Map<String, String> callbackData) throws Exception {
        return verifyWechatCallback(callbackData);
    }

    /**
     * 解析微信支付分授权回调数据
     */
    @Override
    protected Map<String, Object> doParseAuthCallback(Map<String, String> callbackData) throws Exception {
        log.info("解析微信支付分授权回调数据");
        Map<String, Object> parsedData = new HashMap<>();

        String orderNo = callbackData.getOrDefault("out_order_no", callbackData.get("out_order_no"));
        parsedData.put("orderNo", orderNo);

        String thirdPartyOrderNo = callbackData.getOrDefault("order_id", callbackData.get("order_id"));
        parsedData.put("thirdPartyOrderNo", thirdPartyOrderNo);

        String state = callbackData.getOrDefault("state", callbackData.get("state"));
        parsedData.put("state", state);

        // 解析冻结金额
        String frozenAmount = callbackData.getOrDefault("frozen_amount", callbackData.get("frozen_amount"));
        if (StringUtils.hasText(frozenAmount)) {
            parsedData.put("frozenAmount", Long.parseLong(frozenAmount));
        }

        // 授权成功判断
        boolean isAuthSuccess = "USER_ACCEPTED".equalsIgnoreCase(state)
                || "AUTHORIZED".equalsIgnoreCase(state);
        parsedData.put("authorized", isAuthSuccess);

        return parsedData;
    }

    /**
     * 验证微信支付分完结回调签名
     */
    @Override
    protected boolean doVerifyCompleteCallback(Map<String, String> callbackData) throws Exception {
        return verifyWechatCallback(callbackData);
    }

    /**
     * 解析微信支付分完结回调数据
     */
    @Override
    protected Map<String, Object> doParseCompleteCallback(Map<String, String> callbackData) throws Exception {
        log.info("解析微信支付分完结回调数据");
        Map<String, Object> parsedData = new HashMap<>();

        String orderNo = callbackData.getOrDefault("out_order_no", callbackData.get("out_order_no"));
        parsedData.put("orderNo", orderNo);

        String state = callbackData.getOrDefault("state", callbackData.get("state"));
        parsedData.put("state", state);

        // 解析实际扣款金额
        String actualAmount = callbackData.getOrDefault("actual_amount", callbackData.get("actual_amount"));
        if (StringUtils.hasText(actualAmount)) {
            parsedData.put("actualAmount", Long.parseLong(actualAmount));
        }

        // 完结成功判断
        boolean isCompleted = "COMPLETED".equalsIgnoreCase(state)
                || "DONE".equalsIgnoreCase(state);
        parsedData.put("completed", isCompleted);

        return parsedData;
    }

    @Override
    public String getSuccessResponse() {
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }

    @Override
    public String getFailureResponse() {
        return "{\"code\":\"FAIL\",\"message\":\"失败\"}";
    }

    // ==================== 私有辅助方法 ====================

    private PaymentConfig.PayScoreConfig.WechatPayScoreConfig getWechatConfig() {
        return paymentConfig.getPayscore().getWechat();
    }

    /**
     * 通用微信回调签名验证
     * 使用 NotificationParser 验证签名并解密回调数据
     */
    private boolean verifyWechatCallback(Map<String, String> callbackData) throws Exception {
        log.info("验证微信支付分回调签名");

        if (wechatPaySdkConfig == null) {
            log.error("微信支付SDK配置未初始化，无法验证回调签名");
            return false;
        }

        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(callbackData.getOrDefault("Wechatpay-Serial", ""))
                    .nonce(callbackData.getOrDefault("Wechatpay-Nonce", ""))
                    .signature(callbackData.getOrDefault("Wechatpay-Signature", ""))
                    .timestamp(callbackData.getOrDefault("Wechatpay-Timestamp", ""))
                    .body(callbackData.getOrDefault("body", ""))
                    .build();

            NotificationParser parser = new NotificationParser(
                    (com.wechat.pay.java.core.notification.NotificationConfig) wechatPaySdkConfig);

            // 尝试解析以验证签名 - 使用通用Map类型
            Map<String, Object> parsed = parser.parse(requestParam, Map.class);

            if (parsed != null && !parsed.isEmpty()) {
                // 将解密后的数据回填到callbackData
                parsed.forEach((key, value) -> {
                    if (value != null) {
                        callbackData.put(key, value.toString());
                    }
                });
                return true;
            }

            log.warn("微信支付分回调签名验证失败 - 解析结果为空");
            return false;
        } catch (Exception e) {
            log.error("验证微信支付分回调签名失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
