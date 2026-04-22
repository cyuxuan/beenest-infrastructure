package club.beenest.payment.paymentorder.strategy.impl;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import club.beenest.payment.paymentorder.strategy.AbstractPaymentStrategy;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradeCreateModel;
import com.alipay.api.domain.AlipayTradeFastpayRefundQueryModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeCreateRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeCreateResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝支付策略实现
 * 实现支付宝小程序支付功能
 * 
 * <p>支付方式：</p>
 * <ul>
 *   <li>支付宝小程序支付</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Component
@Slf4j
public class AlipayPaymentStrategy extends AbstractPaymentStrategy {
    
    private AlipayClient alipayClient;
    
    public AlipayPaymentStrategy(PaymentConfig paymentConfig) {
        super(paymentConfig);
        initAlipayClient();
    }
    
    private void initAlipayClient() {
        PaymentConfig.AlipayConfig config = paymentConfig.getAlipay();
        if (config.isEnabled()) {
            try {
                this.alipayClient = new DefaultAlipayClient(
                        config.getGatewayUrl(),
                        config.getAppId(),
                        config.getPrivateKey(),
                        config.getFormat(),
                        config.getCharset(),
                        config.getAlipayPublicKey(),
                        config.getSignType()
                );
                log.info("支付宝客户端初始化成功");
            } catch (Exception e) {
                log.error("支付宝客户端初始化失败", e);
            }
        }
    }
    
    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_ALIPAY;
    }
    
    @Override
    public String getPlatformName() {
        return "支付宝";
    }
    
    @Override
    public boolean isEnabled() {
        return paymentConfig.getAlipay().isEnabled() && alipayClient != null;
    }
    
    /**
     * 创建支付宝支付订单
     */
    @Override
    protected Map<String, Object> doCreatePayment(PaymentOrder paymentOrder) throws Exception {
        log.info("创建支付宝支付订单 - orderNo: {}, amount: {}", 
                paymentOrder.getOrderNo(), paymentOrder.getAmount());
        
        checkClient();
        
        AlipayTradeCreateRequest request = new AlipayTradeCreateRequest();
        AlipayTradeCreateModel model = new AlipayTradeCreateModel();
        
        // 必填参数
        model.setOutTradeNo(paymentOrder.getOrderNo());
        model.setTotalAmount(paymentOrder.getAmountInYuan().toString());
        model.setSubject("订单支付-" + paymentOrder.getOrderNo());
        
        // 可选参数
        if (StringUtils.hasText(paymentOrder.getCustomerNo())) {
             // 支付宝小程序支付通常需要 buyer_id (支付宝用户ID)，这里假设 CustomerNo 映射或者通过其他方式获取
             // 如果没有 buyer_id，部分接口可能报错，视具体业务而定
             // model.setBuyerId(getCustomerAlipayId(paymentOrder.getCustomerNo()));
        }
        
        request.setBizModel(model);
        request.setNotifyUrl(paymentOrder.getNotifyUrl());
        
        AlipayTradeCreateResponse response = alipayClient.execute(request);
        
        if (response.isSuccess()) {
            Map<String, Object> paymentParams = new HashMap<>();
            paymentParams.put("tradeNo", response.getTradeNo());
            paymentParams.put("outTradeNo", response.getOutTradeNo());
            log.info("创建支付宝支付订单成功 - orderNo: {}, tradeNo: {}", 
                    paymentOrder.getOrderNo(), response.getTradeNo());
            return paymentParams;
        } else {
            log.error("创建支付宝支付订单失败 - code: {}, msg: {}, subCode: {}, subMsg: {}",
                    response.getCode(), response.getMsg(), response.getSubCode(), response.getSubMsg());
            throw new BusinessException("创建支付宝订单失败: " + response.getSubMsg());
        }
    }
    
    /**
     * 验证支付宝回调签名
     */
    @Override
    protected boolean doVerifyCallback(Map<String, String> callbackData) throws Exception {
        log.info("验证支付宝回调签名");
        
        PaymentConfig.AlipayConfig config = paymentConfig.getAlipay();
        
        // 调用SDK验证签名
        return AlipaySignature.rsaCheckV1(
                callbackData, 
                config.getAlipayPublicKey(), 
                config.getCharset(), 
                config.getSignType()
        );
    }
    
    /**
     * 解析支付宝回调数据
     */
    @Override
    protected Map<String, Object> doParseCallback(Map<String, String> callbackData) throws Exception {
        log.info("解析支付宝回调数据");
        
        Map<String, Object> parsedData = new HashMap<>();
        
        String orderNo = callbackData.get("out_trade_no");
        parsedData.put("orderNo", orderNo);
        
        String transactionNo = callbackData.get("trade_no");
        parsedData.put("transactionNo", transactionNo);
        
        String totalAmount = callbackData.get("total_amount");
        if (totalAmount != null) {
            parsedData.put("amount", new BigDecimal(totalAmount).multiply(new BigDecimal(100)).longValue());
        }
        
        String tradeStatus = callbackData.get("trade_status");
        boolean isSuccess = PaymentConstants.REFUND_STATUS_TRADE_SUCCESS.equals(tradeStatus) || PaymentConstants.REFUND_STATUS_TRADE_FINISHED.equals(tradeStatus);
        parsedData.put("status", isSuccess ? PaymentConstants.PAYMENT_STATUS_PAID : PaymentConstants.REFUND_STATUS_FAILED);
        
        return parsedData;
    }
    
    @Override
    protected Map<String, Object> doQueryPayment(PaymentOrder paymentOrder) throws Exception {
        log.info("查询支付宝订单状态 - orderNo: {}", paymentOrder.getOrderNo());
        
        checkClient();
        
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(paymentOrder.getOrderNo());
        request.setBizModel(model);
        
        AlipayTradeQueryResponse response = alipayClient.execute(request);
        
        if (response.isSuccess()) {
            Map<String, Object> result = new HashMap<>();
            result.put("platformStatus", response.getTradeStatus());
            result.put("transactionNo", response.getTradeNo());
            result.put("amount", new BigDecimal(response.getTotalAmount()).multiply(new BigDecimal(100)).longValue());
            
            String statusMsg;
            switch (response.getTradeStatus()) {
                case PaymentConstants.ALIPAY_TRADE_WAIT_BUYER_PAY: statusMsg = "交易创建，等待买家付款"; break;
                case PaymentConstants.ALIPAY_TRADE_CLOSED: statusMsg = "未付款交易超时关闭，或支付完成后全额退款"; break;
                case PaymentConstants.REFUND_STATUS_TRADE_SUCCESS: statusMsg = "交易支付成功"; break;
                case PaymentConstants.REFUND_STATUS_TRADE_FINISHED: statusMsg = "交易结束，不可退款"; break;
                default: statusMsg = response.getTradeStatus();
            }
            result.put("message", statusMsg);
            
            return result;
        } else {
             if (PaymentConstants.ALIPAY_TRADE_NOT_EXIST.equals(response.getSubCode())) {
                 Map<String, Object> result = new HashMap<>();
                 result.put("platformStatus", PaymentConstants.RECONCILIATION_STATUS_NOT_EXIST);
                 result.put("message", "订单不存在");
                 return result;
             }
             
             log.error("查询支付宝订单失败 - code: {}, msg: {}", response.getCode(), response.getMsg());
             throw new BusinessException("查询支付宝订单失败: " + response.getSubMsg());
        }
    }
    
    /**
     * 取消支付宝订单
     */
    @Override
    public boolean cancelPayment(PaymentOrder paymentOrder) {
        log.info("取消支付宝订单 - orderNo: {}", paymentOrder.getOrderNo());
        
        if (!isEnabled()) return false;
        
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(paymentOrder.getOrderNo());
            request.setBizModel(model);
            
            AlipayTradeCloseResponse response = alipayClient.execute(request);
            
            if (response.isSuccess()) {
                log.info("取消支付宝订单成功 - orderNo: {}", paymentOrder.getOrderNo());
                return true;
            } else {
                log.warn("取消支付宝订单失败 - orderNo: {}, code: {}, msg: {}", 
                        paymentOrder.getOrderNo(), response.getCode(), response.getSubMsg());
                return false;
            }
        } catch (Exception e) {
            log.error("取消支付宝订单异常", e);
            return false;
        }
    }
    
    /**
     * 申请退款
     */
    @Override
    public Map<String, Object> refund(PaymentOrder paymentOrder, Long refundAmount, String refundReason, String refundNo) {
        log.info("申请支付宝退款 - orderNo: {}, amount: {}, refundNo: {}", paymentOrder.getOrderNo(), refundAmount, refundNo);
        
        checkClient();
        
        try {
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(paymentOrder.getOrderNo());
            model.setRefundAmount(new BigDecimal(refundAmount).divide(new BigDecimal(100)).toString());
            model.setRefundReason(refundReason);
            // 标识一次退款请求，同一笔交易多次退款需要保证唯一
            model.setOutRequestNo(refundNo); 
            
            request.setBizModel(model);
            
            AlipayTradeRefundResponse response = alipayClient.execute(request);
            
            if (response.isSuccess()) {
                Map<String, Object> result = new HashMap<>();
                result.put("refundId", response.getTradeNo());
                result.put("fundChange", response.getFundChange());
                result.put("refundFee", response.getRefundFee());
                result.put("status", PaymentConstants.ALIPAY_FUND_CHANGE_YES.equalsIgnoreCase(response.getFundChange()) ? PaymentConstants.REFUND_STATUS_REFUND_SUCCESS : PaymentConstants.REFUND_STATUS_PROCESSING);
                log.info("支付宝退款成功 - orderNo: {}", paymentOrder.getOrderNo());
                return result;
            } else {
                throw new BusinessException("支付宝退款失败: " + response.getSubMsg());
            }
        } catch (Exception e) {
            log.error("支付宝退款异常", e);
            throw new BusinessException("支付宝退款异常: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> queryRefund(PaymentOrder paymentOrder, Refund refund) {
        log.info("查询支付宝退款状态 - orderNo: {}, refundNo: {}", paymentOrder.getOrderNo(), refund.getRefundNo());

        checkClient();

        try {
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            AlipayTradeFastpayRefundQueryModel model = new AlipayTradeFastpayRefundQueryModel();
            model.setOutTradeNo(paymentOrder.getOrderNo());
            model.setOutRequestNo(refund.getRefundNo());
            request.setBizModel(model);

            AlipayTradeFastpayRefundQueryResponse response = alipayClient.execute(request);
            if (!response.isSuccess()) {
                throw new BusinessException("支付宝退款查询失败: " + response.getSubMsg());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("refundId", response.getTradeNo());
            result.put("outRefundNo", response.getOutRequestNo());
            result.put("status", response.getRefundStatus());
            result.put("channelStatus", response.getRefundChannelStatus());
            if (StringUtils.hasText(response.getRefundAmount())) {
                result.put("amount", new BigDecimal(response.getRefundAmount()).multiply(new BigDecimal(100)).longValue());
            }
            if (StringUtils.hasText(response.getErrorCode())) {
                result.put("errorCode", response.getErrorCode());
            }
            return result;
        } catch (Exception e) {
            log.error("查询支付宝退款状态失败 - orderNo: {}, refundNo: {}, error: {}",
                    paymentOrder.getOrderNo(), refund.getRefundNo(), e.getMessage(), e);
            throw new BusinessException("支付宝退款查询异常: " + e.getMessage(), e);
        }
    }

    @Override
    public String getSuccessResponse() {
        return "success";
    }
    
    @Override
    public String getFailureResponse() {
        return "failure";
    }
    
    private void checkClient() {
        if (alipayClient == null) {
            throw new BusinessException("支付宝客户端未初始化");
        }
    }
}
