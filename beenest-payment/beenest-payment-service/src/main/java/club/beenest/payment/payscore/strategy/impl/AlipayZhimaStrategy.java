package club.beenest.payment.payscore.strategy.impl;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.strategy.AbstractPayScoreStrategy;
import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.PaymentConstants;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.ZhimaCreditPayafteruseCreditbizorderCreateModel;
import com.alipay.api.domain.ZhimaCreditPayafteruseCreditbizorderFinishModel;
import com.alipay.api.domain.ZhimaCreditPayafteruseCreditbizorderQueryModel;
import com.alipay.api.domain.ZhimaCreditEpFreedepositInitializeModel;
import com.alipay.api.domain.ZhimaCreditEpFreedepositApplyModel;
import com.alipay.api.domain.ZhimaCreditScoreGetModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.ZhimaCreditEpFreedepositApplyRequest;
import com.alipay.api.request.ZhimaCreditEpFreedepositInitializeRequest;
import com.alipay.api.request.ZhimaCreditPayafteruseCreditbizorderCreateRequest;
import com.alipay.api.request.ZhimaCreditPayafteruseCreditbizorderFinishRequest;
import com.alipay.api.request.ZhimaCreditPayafteruseCreditbizorderQueryRequest;
import com.alipay.api.request.ZhimaCreditScoreGetRequest;
import com.alipay.api.response.ZhimaCreditEpFreedepositApplyResponse;
import com.alipay.api.response.ZhimaCreditEpFreedepositInitializeResponse;
import com.alipay.api.response.ZhimaCreditPayafteruseCreditbizorderCreateResponse;
import com.alipay.api.response.ZhimaCreditPayafteruseCreditbizorderFinishResponse;
import com.alipay.api.response.ZhimaCreditPayafteruseCreditbizorderQueryResponse;
import com.alipay.api.response.ZhimaCreditScoreGetResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝芝麻信用策略实现
 * 实现支付宝芝麻信用先享后付（信用免押）功能
 *
 * <p>核心API（支付宝开放平台）：</p>
 * <ul>
 *   <li>芝麻分查询：ZhimaCreditScoreGetRequest</li>
 *   <li>免押金初始化：ZhimaCreditEpFreedepositInitializeRequest</li>
 *   <li>免押金申请：ZhimaCreditEpFreedepositApplyRequest</li>
 *   <li>创建先享后付订单：ZhimaCreditPayafteruseCreditbizorderCreateRequest</li>
 *   <li>完结先享后付订单：ZhimaCreditPayafteruseCreditbizorderFinishRequest</li>
 *   <li>查询先享后付订单：ZhimaCreditPayafteruseCreditbizorderQueryRequest</li>
 * </ul>
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li>免押金模式 (Freedeposit) - 商户入驻免押场景</li>
 *   <li>先享后付模式 (Payafteruse) - 先服务后付款场景</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Component
@Slf4j
public class AlipayZhimaStrategy extends AbstractPayScoreStrategy {

    private AlipayClient alipayClient;

    public AlipayZhimaStrategy(PaymentConfig paymentConfig) {
        super(paymentConfig);
        initAlipayClient();
    }

    /**
     * 初始化支付宝客户端
     */
    private void initAlipayClient() {
        PaymentConfig.AlipayConfig alipayConfig = paymentConfig.getAlipay();
        if (alipayConfig.isEnabled()) {
            try {
                this.alipayClient = new DefaultAlipayClient(
                        alipayConfig.getGatewayUrl(),
                        alipayConfig.getAppId(),
                        alipayConfig.getPrivateKey(),
                        alipayConfig.getFormat(),
                        alipayConfig.getCharset(),
                        alipayConfig.getAlipayPublicKey(),
                        alipayConfig.getSignType());
                log.info("支付宝客户端初始化成功（芝麻信用免押）");
            } catch (Exception e) {
                log.error("支付宝客户端初始化失败", e);
            }
        }
    }

    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_ALIPAY_ZHIMA;
    }

    @Override
    public String getPlatformName() {
        return "支付宝芝麻信用";
    }

    @Override
    public boolean isEnabled() {
        PaymentConfig.PayScoreConfig.AlipayZhimaConfig config = getZhimaConfig();
        return config.isEnabled() && alipayClient != null;
    }

    /**
     * 信用免押检查
     *
     * <p>调用芝麻信用分查询API，获取用户芝麻分，
     * 根据配置的最低信用分阈值判断是否满足免押条件。</p>
     *
     * <p>注意：ZhimaCreditScoreGetRequest 需要用户授权（需传入授权token），
     * 在用户未授权的场景下返回默认不允许免押。</p>
     */
    @Override
    protected CreditCheckResultDTO doCheckCreditEligibility(String userId, String serviceId, long depositAmount) throws Exception {
        log.info("支付宝芝麻信用免押检查 - userId: {}, depositAmount: {}", userId, depositAmount);
        checkAlipayClient();

        PaymentConfig.PayScoreConfig.PayScoreCommonConfig commonConfig = paymentConfig.getPayscore().getCommon();

        CreditCheckResultDTO result = new CreditCheckResultDTO();
        result.setCustomerNo(userId);
        result.setPlatform(getPlatform());
        result.setDepositAmount(depositAmount);

        try {
            // 查询芝麻分 - 需要用户授权token
            ZhimaCreditScoreGetRequest request = new ZhimaCreditScoreGetRequest();
            ZhimaCreditScoreGetModel model = new ZhimaCreditScoreGetModel();
            model.setProductCode(getZhimaConfig().getProductCode());
            // transactionId 用于标识此次查询（需传入业务流水号）
            model.setTransactionId("CREDIT_CHECK_" + userId + "_" + System.nanoTime());
            request.setBizModel(model);

            ZhimaCreditScoreGetResponse response = alipayClient.execute(request);

            if (response.isSuccess() && response.getZmScore() != null) {
                int creditScore = Integer.parseInt(response.getZmScore());
                result.setCreditScore(creditScore);

                if (creditScore >= commonConfig.getMinCreditScore()) {
                    // 信用分达标，完全免押
                    result.setEligible(true);
                    result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_FULL);
                    result.setFrozenAmount(0L);
                    result.setMessage("芝麻分" + creditScore + "，满足免押条件");
                } else {
                    // 信用分不足，不满足免押
                    result.setEligible(false);
                    result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_NONE);
                    result.setFrozenAmount(depositAmount);
                    result.setMessage("芝麻分" + creditScore + "，未达到免押门槛" + commonConfig.getMinCreditScore());
                }
            } else {
                // 无法获取芝麻分，默认不满足免押
                result.setEligible(false);
                result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_NONE);
                result.setFrozenAmount(depositAmount);
                result.setMessage("无法获取芝麻信用分，不满足免押条件");
            }
        } catch (Exception e) {
            log.error("查询芝麻信用分失败 - userId: {}, error: {}", userId, e.getMessage(), e);
            result.setEligible(false);
            result.setExemptionResult(PaymentConstants.CREDIT_EXEMPTION_NONE);
            result.setFrozenAmount(depositAmount);
            result.setMessage("芝麻信用分查询失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * 创建支付宝芝麻信用先享后付订单
     *
     * <p>使用 ZhimaCreditPayafteruseCreditbizorderCreateRequest 创建信用订单，
     * 适用于先服务后付款的场景。商户入驻保证金免押更适合使用 Freedeposit API。</p>
     */
    @Override
    protected Map<String, Object> doCreateServiceOrder(ServiceOrder serviceOrder) throws Exception {
        log.info("创建支付宝芝麻信用先享后付订单 - orderNo: {}", serviceOrder.getOrderNo());
        checkAlipayClient();

        // 使用免押金初始化模式（更适合商户保证金免押场景）
        ZhimaCreditEpFreedepositInitializeRequest initRequest = new ZhimaCreditEpFreedepositInitializeRequest();
        ZhimaCreditEpFreedepositInitializeModel initModel = new ZhimaCreditEpFreedepositInitializeModel();
        initModel.setProductCode(getZhimaConfig().getProductCode());
        initModel.setMerchantOrderNo(serviceOrder.getOrderNo());
        initModel.setOutRequestNo(serviceOrder.getOrderNo());
        initModel.setCreditCategory("COMMERCIAL"); // 商业免押
        initRequest.setBizModel(initModel);
        initRequest.setNotifyUrl(serviceOrder.getNotifyUrl());

        ZhimaCreditEpFreedepositInitializeResponse initResponse = alipayClient.execute(initRequest);

        if (initResponse.isSuccess()) {
            Map<String, Object> authParams = new HashMap<>();
            authParams.put("orderNo", initResponse.getOrderNo());

            log.info("创建支付宝芝麻信用免押订单成功 - orderNo: {}, alipayOrderNo: {}",
                    serviceOrder.getOrderNo(), initResponse.getOrderNo());
            return authParams;
        } else {
            log.error("创建支付宝芝麻信用免押订单失败 - code: {}, msg: {}, subCode: {}, subMsg: {}",
                    initResponse.getCode(), initResponse.getMsg(),
                    initResponse.getSubCode(), initResponse.getSubMsg());
            throw new BusinessException("创建芝麻信用订单失败: " + initResponse.getSubMsg());
        }
    }

    /**
     * 查询支付宝芝麻信用订单
     */
    @Override
    protected Map<String, Object> doQueryServiceOrder(ServiceOrder serviceOrder) throws Exception {
        log.info("查询支付宝芝麻信用订单 - orderNo: {}", serviceOrder.getOrderNo());
        checkAlipayClient();

        ZhimaCreditPayafteruseCreditbizorderQueryRequest request = new ZhimaCreditPayafteruseCreditbizorderQueryRequest();
        ZhimaCreditPayafteruseCreditbizorderQueryModel model = new ZhimaCreditPayafteruseCreditbizorderQueryModel();
        model.setOutOrderNo(serviceOrder.getOrderNo());
        request.setBizModel(model);

        ZhimaCreditPayafteruseCreditbizorderQueryResponse response = alipayClient.execute(request);

        Map<String, Object> result = new HashMap<>();
        if (response.isSuccess()) {
            result.put("platformStatus", response.getOrderStatus());
            result.put("thirdPartyOrderNo", response.getCreditBizOrderId());
        } else {
            result.put("platformStatus", "QUERY_FAILED");
            result.put("message", response.getSubMsg());
        }

        return result;
    }

    /**
     * 完结支付宝芝麻信用订单
     */
    @Override
    protected Map<String, Object> doCompleteServiceOrder(ServiceOrder serviceOrder, long actualAmount) throws Exception {
        log.info("完结支付宝芝麻信用订单 - orderNo: {}, actualAmount: {}", serviceOrder.getOrderNo(), actualAmount);
        checkAlipayClient();

        ZhimaCreditPayafteruseCreditbizorderFinishRequest request = new ZhimaCreditPayafteruseCreditbizorderFinishRequest();
        ZhimaCreditPayafteruseCreditbizorderFinishModel model = new ZhimaCreditPayafteruseCreditbizorderFinishModel();
        // 使用 creditBizOrderId 或 outRequestNo 标识订单
        model.setOutRequestNo(serviceOrder.getOrderNo());
        model.setIsFulfilled(actualAmount > 0 ? "Y" : "N"); // 是否实际扣款
        model.setRemark(actualAmount > 0 ? "扣款" + actualAmount + "分" : "全额解冻");
        request.setBizModel(model);

        ZhimaCreditPayafteruseCreditbizorderFinishResponse response = alipayClient.execute(request);

        Map<String, Object> result = new HashMap<>();
        if (response.isSuccess()) {
            result.put("platformStatus", "COMPLETED");
            result.put("success", true);
            log.info("完结支付宝芝麻信用订单成功 - orderNo: {}", serviceOrder.getOrderNo());
        } else {
            log.error("完结支付宝芝麻信用订单失败 - code: {}, subMsg: {}",
           response.getCode(), response.getSubMsg());
            throw new BusinessException("完结芝麻信用订单失败: " + response.getSubMsg());
        }

        return result;
    }

    /**
     * 取消支付宝芝麻信用订单
     *
     * <p>通过芝麻免押金申请取消接口实现解冻。</p>
     */
    @Override
    protected Map<String, Object> doCancelServiceOrder(ServiceOrder serviceOrder, String reason) throws Exception {
        log.info("取消支付宝芝麻信用订单 - orderNo: {}, reason: {}", serviceOrder.getOrderNo(), reason);
        checkAlipayClient();

        // 使用免押金申请接口取消（传入0元实现全额解冻）
        ZhimaCreditEpFreedepositApplyRequest request = new ZhimaCreditEpFreedepositApplyRequest();
        ZhimaCreditEpFreedepositApplyModel model = new ZhimaCreditEpFreedepositApplyModel();
        model.setOrderNo(serviceOrder.getOrderNo());
        model.setMerchantOrderNo(serviceOrder.getOrderNo());
        request.setBizModel(model);

        ZhimaCreditEpFreedepositApplyResponse response = alipayClient.execute(request);

        Map<String, Object> result = new HashMap<>();
        if (response.isSuccess()) {
            result.put("success", true);
            result.put("message", "取消成功");
            log.info("取消支付宝芝麻信用订单成功 - orderNo: {}", serviceOrder.getOrderNo());
        } else {
            throw new BusinessException("取消芝麻信用订单失败: " + response.getSubMsg());
        }

        return result;
    }

    /**
     * 修改支付宝芝麻信用订单金额
     *
     * <p>支付宝芝麻信用不直接支持修改冻结金额，
     * 需通过完结+重新创建的方式实现，此处返回不支持的提示。</p>
     */
    @Override
    protected Map<String, Object> doModifyServiceOrder(ServiceOrder serviceOrder, long newAmount) throws Exception {
        log.warn("支付宝芝麻信用不支持直接修改冻结金额 - orderNo: {}", serviceOrder.getOrderNo());
        throw new BusinessException("支付宝芝麻信用暂不支持修改冻结金额，请取消当前订单后重新创建");
    }

    /**
     * 验证支付宝授权回调签名
     */
    @Override
    protected boolean doVerifyAuthCallback(Map<String, String> callbackData) throws Exception {
        return verifyAlipayCallback(callbackData);
    }

    /**
     * 解析支付宝授权回调数据
     */
    @Override
    protected Map<String, Object> doParseAuthCallback(Map<String, String> callbackData) throws Exception {
        return parseAlipayCallback(callbackData);
    }

    /**
     * 验证支付宝完结回调签名
     */
    @Override
    protected boolean doVerifyCompleteCallback(Map<String, String> callbackData) throws Exception {
        return verifyAlipayCallback(callbackData);
    }

    /**
     * 解析支付宝完结回调数据
     */
    @Override
    protected Map<String, Object> doParseCompleteCallback(Map<String, String> callbackData) throws Exception {
        log.info("解析支付宝芝麻信用完结回调数据");
        Map<String, Object> parsedData = parseAlipayCallback(callbackData);

        // 额外提取完结相关字段
        String creditAmount = callbackData.getOrDefault("credit_amount", "");
        if (StringUtils.hasText(creditAmount)) {
            parsedData.put("actualAmount", Long.parseLong(creditAmount));
        }

        String orderStatus = callbackData.getOrDefault("order_status", "");
        parsedData.put("completed", "COMPLETED".equalsIgnoreCase(orderStatus)
                || "FINISHED".equalsIgnoreCase(orderStatus));

        return parsedData;
    }

    @Override
    public String getSuccessResponse() {
        return "success";
    }

    @Override
    public String getFailureResponse() {
        return "failure";
    }

    // ==================== 私有辅助方法 ====================

    private PaymentConfig.PayScoreConfig.AlipayZhimaConfig getZhimaConfig() {
        return paymentConfig.getPayscore().getAlipay();
    }

    private void checkAlipayClient() {
        if (alipayClient == null) {
            throw new BusinessException("支付宝客户端未初始化");
        }
    }

    /**
     * 通用支付宝回调签名验证
     */
    private boolean verifyAlipayCallback(Map<String, String> callbackData) throws Exception {
        log.info("验证支付宝芝麻信用回调签名");

        PaymentConfig.AlipayConfig alipayConfig = paymentConfig.getAlipay();
        return AlipaySignature.rsaCheckV1(
                callbackData,
                alipayConfig.getAlipayPublicKey(),
                alipayConfig.getCharset(),
                alipayConfig.getSignType());
    }

    /**
     * 通用支付宝回调数据解析
     */
    private Map<String, Object> parseAlipayCallback(Map<String, String> callbackData) {
        Map<String, Object> parsedData = new HashMap<>();

        String orderNo = callbackData.getOrDefault("out_order_no", "");
        parsedData.put("orderNo", orderNo);

        String tradeNo = callbackData.getOrDefault("trade_no", "");
        parsedData.put("thirdPartyOrderNo", tradeNo);

        String orderStatus = callbackData.getOrDefault("order_status", "");
        parsedData.put("state", orderStatus);

        String freezeAmount = callbackData.getOrDefault("freeze_amount", "");
        if (StringUtils.hasText(freezeAmount)) {
            parsedData.put("frozenAmount", Long.parseLong(freezeAmount));
        }

        // 授权成功判断
        boolean isAuthSuccess = "AUTHORIZED".equalsIgnoreCase(orderStatus)
                || "FREEZE".equalsIgnoreCase(orderStatus);
        parsedData.put("authorized", isAuthSuccess);

        return parsedData;
    }
}
