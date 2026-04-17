package club.beenest.payment.strategy.impl;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.config.PaymentConfig;
import club.beenest.payment.constant.PaymentConstants;
import club.beenest.payment.object.entity.Refund;
import club.beenest.payment.strategy.AbstractPaymentStrategy;
import club.beenest.payment.object.entity.PaymentOrder;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付策略实现
 * 实现微信小程序支付功能
 * 
 * <p>支付方式：</p>
 * <ul>
 *   <li>微信小程序支付（JSAPI）</li>
 * </ul>
 * 
 * <h3>API文档：</h3>
 * <ul>
 *   <li>JSAPI下单：https://pay.weixin.qq.com/doc/v3/merchant/4012791897</li>
 *   <li>查询订单：https://pay.weixin.qq.com/doc/v3/merchant/4012791898</li>
 *   <li>关闭订单：https://pay.weixin.qq.com/doc/v3/merchant/4012791899</li>
 *   <li>支付通知：https://pay.weixin.qq.com/doc/v3/merchant/4012791900</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Component
@Slf4j
public class WechatPaymentStrategy extends AbstractPaymentStrategy {
    
    @Autowired(required = false)
    private JsapiServiceExtension jsapiService;
    
    @Autowired(required = false)
    private Config wechatPaySdkConfig;
    
    @Autowired(required = false)
    private RefundService refundService;
    
    public WechatPaymentStrategy(PaymentConfig paymentConfig) {
        super(paymentConfig);
    }
    
    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_WECHAT;
    }
    
    @Override
    public String getPlatformName() {
        return "微信支付";
    }
    
    @Override
    public boolean isEnabled() {
        return paymentConfig.getWechat().isEnabled() && jsapiService != null;
    }
    
    /**
     * 创建微信支付订单
     * 
     * <p>调用微信JSAPI下单接口，返回小程序支付参数。</p>
     * 
     * <h4>实现步骤：</h4>
     * <ol>
     *   <li>构建JSAPI下单请求</li>
     *   <li>调用微信JSAPI下单API</li>
     *   <li>获取prepay_id</li>
     *   <li>使用SDK生成小程序支付参数</li>
     * </ol>
     * 
     * <h4>返回参数：</h4>
     * <ul>
     *   <li>timeStamp - 时间戳</li>
     *   <li>nonceStr - 随机字符串</li>
     *   <li>package - 预支付交易会话标识</li>
     *   <li>signType - 签名类型（RSA）</li>
     *   <li>paySign - 支付签名</li>
     * </ul>
     */
    @Override
    protected Map<String, Object> doCreatePayment(PaymentOrder paymentOrder) throws Exception {
        log.info("创建微信支付订单 - orderNo: {}, amount: {}", 
                paymentOrder.getOrderNo(), paymentOrder.getAmount());
        
        if (jsapiService == null) {
            throw new RuntimeException("微信支付服务未初始化");
        }
        
        // 1. 构建JSAPI下单请求
        PrepayRequest request = new PrepayRequest();
        
        // 应用ID
        request.setAppid(paymentConfig.getWechat().getAppId());
        
        // 商户号
        request.setMchid(paymentConfig.getWechat().getMchId());
        
        // 商品描述
        request.setDescription("充值 - " + paymentOrder.getAmountInYuan() + "元");
        
        // 商户订单号
        request.setOutTradeNo(paymentOrder.getOrderNo());
        
        // 通知地址
        request.setNotifyUrl(paymentOrder.getNotifyUrl());
        
        // 订单金额
        Amount amount = new Amount();
        amount.setTotal(paymentOrder.getAmount().intValue());  // 金额（分）
        amount.setCurrency(PaymentConstants.CURRENCY_CNY);
        request.setAmount(amount);
        
        // 支付者信息（小程序需要openid）
        Payer payer = new Payer();
        String openid = extractOpenidFromOrder(paymentOrder);
        if (!StringUtils.hasText(openid)) {
            throw new RuntimeException("用户微信openid不存在，无法创建支付订单");
        }
        payer.setOpenid(openid);
        request.setPayer(payer);
        
        // 订单优惠标记（可选）
        if (StringUtils.hasText(paymentOrder.getRemark())) {
            request.setAttach(paymentOrder.getRemark());
        }
        
        // 2. 调用微信JSAPI下单API
        log.info("调用微信JSAPI下单API - orderNo: {}", paymentOrder.getOrderNo());
        PrepayWithRequestPaymentResponse response = jsapiService.prepayWithRequestPayment(request);
        
        // 3. 构建小程序支付参数（SDK已自动生成签名）
        Map<String, Object> paymentParams = new HashMap<>();
        paymentParams.put("timeStamp", response.getTimeStamp());
        paymentParams.put("nonceStr", response.getNonceStr());
        paymentParams.put("package", response.getPackageVal());
        paymentParams.put("signType", response.getSignType());
        paymentParams.put("paySign", response.getPaySign());
        
        log.info("创建微信支付订单成功 - orderNo: {}", paymentOrder.getOrderNo());
        return paymentParams;
    }
    
    /**
     * 验证微信支付回调签名
     * 
     * <p>使用微信支付SDK验证回调签名和证书。</p>
     * 
     * <h4>验证步骤：</h4>
     * <ol>
     *   <li>获取回调签名相关参数</li>
     *   <li>使用SDK的NotificationParser验证签名</li>
     *   <li>解密回调数据</li>
     * </ol>
     */
    @Override
    protected boolean doVerifyCallback(Map<String, String> callbackData) throws Exception {
        log.info("验证微信支付回调签名");
        
        if (wechatPaySdkConfig == null) {
            log.error("微信支付配置未初始化");
            return false;
        }
        
        try {
            // 构建请求参数
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(getHeader(callbackData, "Wechatpay-Serial"))
                    .nonce(getHeader(callbackData, "Wechatpay-Nonce"))
                    .signature(getHeader(callbackData, "Wechatpay-Signature"))
                    .timestamp(getHeader(callbackData, "Wechatpay-Timestamp"))
                    .body(callbackData.get("body"))
                    .build();
            
            // 使用SDK验证签名并解密（使用正确的构造函数）
            NotificationParser parser = new NotificationParser((com.wechat.pay.java.core.notification.NotificationConfig) wechatPaySdkConfig);
            Transaction transaction = parser.parse(requestParam, Transaction.class);
            
            if (transaction != null) {
                log.info("微信支付回调签名验证通过 - transactionId: {}", 
                        transaction.getTransactionId());
                
                // 将解密后的数据存回callbackData供后续使用
                callbackData.put("out_trade_no", transaction.getOutTradeNo());
                callbackData.put("transaction_id", transaction.getTransactionId());
                callbackData.put("trade_state", transaction.getTradeState().name());
                if (transaction.getAmount() != null) {
                    callbackData.put("total", String.valueOf(transaction.getAmount().getTotal()));
                }
                
                return true;
            }
            
            log.warn("微信支付回调签名验证失败 - 解析结果为空");
            return false;
            
        } catch (Exception e) {
            log.error("验证微信支付回调签名失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 解析微信支付回调数据
     * 
     * <p>从回调数据中提取订单信息。</p>
     * 
     * <h4>提取字段：</h4>
     * <ul>
     *   <li>out_trade_no - 商户订单号</li>
     *   <li>transaction_id - 微信支付订单号</li>
     *   <li>total - 订单金额（分）</li>
     *   <li>trade_state - 交易状态</li>
     * </ul>
     */
    @Override
    protected Map<String, Object> doParseCallback(Map<String, String> callbackData) throws Exception {
        log.info("解析微信支付回调数据");
        
        Map<String, Object> parsedData = new HashMap<>();
        
        // 提取订单号
        String orderNo = callbackData.get("out_trade_no");
        parsedData.put("orderNo", orderNo);
        
        // 提取第三方交易号
        String transactionNo = callbackData.get("transaction_id");
        parsedData.put("transactionNo", transactionNo);
        
        // 提取支付金额（分）
        String total = callbackData.get("total");
        if (total != null) {
            parsedData.put("amount", Long.parseLong(total));
        }
        
        // 提取交易状态
        String tradeState = callbackData.get("trade_state");
        parsedData.put("status", PaymentConstants.REFUND_STATUS_SUCCESS.equals(tradeState) ? PaymentConstants.PAYMENT_STATUS_PAID : PaymentConstants.REFUND_STATUS_FAILED);
        
        log.info("解析微信支付回调数据成功 - orderNo: {}", orderNo);
        return parsedData;
    }
    
    /**
     * 查询微信支付订单状态
     * 
     * <p>调用微信查询订单API。</p>
     * 
     * <h4>查询步骤：</h4>
     * <ol>
     *   <li>构建查询请求</li>
     *   <li>调用微信查询订单API</li>
     *   <li>解析查询结果</li>
     *   <li>返回订单状态</li>
     * </ol>
     */
    @Override
    protected Map<String, Object> doQueryPayment(PaymentOrder paymentOrder) throws Exception {
        log.info("查询微信支付订单状态 - orderNo: {}", paymentOrder.getOrderNo());
        
        if (jsapiService == null) {
            throw new RuntimeException("微信支付服务未初始化");
        }
        
        try {
            // 构建查询请求
            QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
            request.setMchid(paymentConfig.getWechat().getMchId());
            request.setOutTradeNo(paymentOrder.getOrderNo());
            
            // 调用微信查询订单API
            Transaction transaction = jsapiService.queryOrderByOutTradeNo(request);
            
            // 解析查询结果
            Map<String, Object> queryResult = new HashMap<>();
            queryResult.put("platformStatus", transaction.getTradeState().name());
            queryResult.put("transactionId", transaction.getTransactionId());
            
            if (transaction.getAmount() != null) {
                queryResult.put("amount", transaction.getAmount().getTotal());
            }
            
            // 根据交易状态设置消息
            switch (transaction.getTradeState()) {
                case SUCCESS:
                    queryResult.put("message", "支付成功");
                    break;
                case NOTPAY:
                    queryResult.put("message", "未支付");
                    break;
                case CLOSED:
                    queryResult.put("message", "已关闭");
                    break;
                case REFUND:
                    queryResult.put("message", "转入退款");
                    break;
                case USERPAYING:
                    queryResult.put("message", "用户支付中");
                    break;
                case PAYERROR:
                    queryResult.put("message", "支付失败");
                    break;
                default:
                    queryResult.put("message", "未知状态");
            }
            
            log.info("查询微信支付订单状态成功 - orderNo: {}, status: {}", 
                    paymentOrder.getOrderNo(), transaction.getTradeState());
            
            return queryResult;
            
        } catch (Exception e) {
            log.error("查询微信支付订单状态失败 - orderNo: {}, error: {}", 
                    paymentOrder.getOrderNo(), e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 取消微信支付订单
     * 
     * <p>调用微信关闭订单API。</p>
     */
    @Override
    public boolean cancelPayment(PaymentOrder paymentOrder) {
        log.info("取消微信支付订单 - orderNo: {}", paymentOrder.getOrderNo());
        
        if (jsapiService == null) {
            log.error("微信支付服务未初始化");
            return false;
        }
        
        try {
            // 构建关闭订单请求
            CloseOrderRequest request = new CloseOrderRequest();
            request.setMchid(paymentConfig.getWechat().getMchId());
            request.setOutTradeNo(paymentOrder.getOrderNo());
            
            // 调用微信关闭订单API
            jsapiService.closeOrder(request);
            
            log.info("取消微信支付订单成功 - orderNo: {}", paymentOrder.getOrderNo());
            return true;
            
        } catch (Exception e) {
            log.error("取消微信支付订单失败 - orderNo: {}, error: {}", 
                    paymentOrder.getOrderNo(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 申请退款
     */
    @Override
    public Map<String, Object> refund(PaymentOrder paymentOrder, Long refundAmount, String refundReason, String refundNo) {
        log.info("申请微信退款 - orderNo: {}, amount: {}, refundNo: {}", paymentOrder.getOrderNo(), refundAmount, refundNo);
        
        if (refundService == null) {
            throw new BusinessException("微信退款服务未初始化");
        }
        
        try {
            CreateRequest request = new CreateRequest();
            request.setOutTradeNo(paymentOrder.getOrderNo());
            request.setOutRefundNo(refundNo);
            request.setReason(refundReason);
            request.setNotifyUrl(resolveRefundNotifyUrl());
            
            AmountReq amount = new AmountReq();
            amount.setRefund(refundAmount);
            amount.setTotal(paymentOrder.getAmount());
            amount.setCurrency(PaymentConstants.CURRENCY_CNY);
            request.setAmount(amount);
            
            com.wechat.pay.java.service.refund.model.Refund refund = refundService.create(request);
            
            Map<String, Object> result = new HashMap<>();
            result.put("refundId", refund.getRefundId());
            result.put("outRefundNo", refund.getOutRefundNo());
            result.put("status", refund.getStatus().name());
            
            log.info("微信退款申请成功 - orderNo: {}, refundId: {}", 
                    paymentOrder.getOrderNo(), refund.getRefundId());
            
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("微信退款申请失败 - orderNo: {}, error: {}",
                    paymentOrder.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("微信退款申请失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> queryRefund(PaymentOrder paymentOrder, Refund refund) {
        log.info("查询微信退款状态 - orderNo: {}, refundNo: {}", paymentOrder.getOrderNo(), refund.getRefundNo());

        if (refundService == null) {
            throw new BusinessException("微信退款服务未初始化");
        }

        try {
            QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
            request.setOutRefundNo(refund.getRefundNo());

            com.wechat.pay.java.service.refund.model.Refund response = refundService.queryByOutRefundNo(request);

            Map<String, Object> result = new HashMap<>();
            result.put("refundId", response.getRefundId());
            result.put("outRefundNo", response.getOutRefundNo());
            result.put("status", response.getStatus() == null ? null : response.getStatus().name());
            result.put("successTime", response.getSuccessTime());
            if (response.getAmount() != null) {
                result.put("amount", response.getAmount().getRefund());
            }
            return result;
        } catch (Exception e) {
            log.error("查询微信退款状态失败 - orderNo: {}, refundNo: {}, error: {}",
                    paymentOrder.getOrderNo(), refund.getRefundNo(), e.getMessage(), e);
            throw new BusinessException("查询微信退款状态失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyRefundCallback(Map<String, String> callbackData) {
        log.info("验证微信退款回调签名");

        if (wechatPaySdkConfig == null) {
            log.error("微信支付配置未初始化");
            return false;
        }

        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(getHeader(callbackData, "Wechatpay-Serial"))
                    .nonce(getHeader(callbackData, "Wechatpay-Nonce"))
                    .signature(getHeader(callbackData, "Wechatpay-Signature"))
                    .timestamp(getHeader(callbackData, "Wechatpay-Timestamp"))
                    .body(callbackData.get("body"))
                    .build();

            NotificationParser parser = new NotificationParser((com.wechat.pay.java.core.notification.NotificationConfig) wechatPaySdkConfig);
            RefundNotification notification = parser.parse(requestParam, RefundNotification.class);
            if (notification == null) {
                log.warn("微信退款回调签名验证失败 - 解析结果为空");
                return false;
            }

            callbackData.put("refund_id", notification.getRefundId());
            callbackData.put("out_refund_no", notification.getOutRefundNo());
            callbackData.put("transaction_id", notification.getTransactionId());
            callbackData.put("out_trade_no", notification.getOutTradeNo());
            callbackData.put("refund_status", notification.getRefundStatus() == null ? null : notification.getRefundStatus().name());
            if (notification.getAmount() != null && notification.getAmount().getRefund() != null) {
                callbackData.put("refund", String.valueOf(notification.getAmount().getRefund()));
            }
            return true;
        } catch (Exception e) {
            log.error("验证微信退款回调签名失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Map<String, Object> parseRefundCallback(Map<String, String> callbackData) {
        log.info("解析微信退款回调数据");

        Map<String, Object> parsedData = new HashMap<>();
        parsedData.put("refundNo", callbackData.get("out_refund_no"));
        parsedData.put("orderNo", callbackData.get("out_trade_no"));
        parsedData.put("refundId", callbackData.get("refund_id"));
        parsedData.put("transactionNo", callbackData.get("transaction_id"));
        parsedData.put("status", callbackData.get("refund_status"));
        String refundAmount = callbackData.get("refund");
        if (StringUtils.hasText(refundAmount)) {
            parsedData.put("amount", Long.parseLong(refundAmount));
        }
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
    
    /**
     * 从PaymentOrder中提取openid
     * 调用方在创建支付时，将openid存入PaymentOrder.returnUrl或remark
     */
    private String extractOpenidFromOrder(PaymentOrder paymentOrder) {
        // 优先从returnUrl读取openid（调用方可将openid放在returnUrl字段）
        if (StringUtils.hasText(paymentOrder.getReturnUrl())
                && paymentOrder.getReturnUrl().startsWith("openid:")) {
            return paymentOrder.getReturnUrl().substring("openid:".length());
        }
        // 其次从remark中读取（格式：openid:oXXXX）
        if (StringUtils.hasText(paymentOrder.getRemark())) {
            String remark = paymentOrder.getRemark();
            int idx = remark.indexOf("openid:");
            if (idx >= 0) {
                String sub = remark.substring(idx + "openid:".length());
                int spaceIdx = sub.indexOf(' ');
                return spaceIdx > 0 ? sub.substring(0, spaceIdx) : sub;
            }
        }
        return null;
    }

    private String resolveRefundNotifyUrl() {
        if (StringUtils.hasText(paymentConfig.getWechat().getRefundNotifyUrl())) {
            return paymentConfig.getWechat().getRefundNotifyUrl();
        }
        return paymentConfig.getWechat().getNotifyUrl();
    }

    private String getHeader(Map<String, String> data, String key) {
        String value = data.get(key);
        return value != null ? value : data.get(key.toLowerCase());
    }
}
