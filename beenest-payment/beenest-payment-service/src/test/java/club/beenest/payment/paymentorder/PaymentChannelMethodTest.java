package club.beenest.payment.paymentorder;

import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.shared.constant.PaymentConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付渠道方式约束测试。
 * <p>
 * 用于确保微信 App 支付与微信 JSAPI 支付的默认值和身份要求被正确区分。
 * </p>
 */
class PaymentChannelMethodTest {

    @Test
    void rechargeWechatDefaultsToAppPayment() {
        RechargeRequestDTO request = new RechargeRequestDTO();
        request.setPlatform(PaymentConstants.PLATFORM_WECHAT);

        assertEquals(PaymentConstants.METHOD_WECHAT_APP, request.getDefaultPaymentMethod());
        assertFalse(PaymentConstants.requiresWechatOpenid(request.getDefaultPaymentMethod()));
    }

    @Test
    void orderPaymentWechatJsapiRequiresOpenid() {
        OrderPaymentRequestDTO request = new OrderPaymentRequestDTO();
        request.setPayType("wxpay");
        request.setPaymentMethod(PaymentConstants.METHOD_WECHAT_JSAPI);

        assertEquals(PaymentConstants.METHOD_WECHAT_JSAPI, request.getDefaultPaymentMethod());
        assertTrue(PaymentConstants.requiresWechatOpenid(request.getDefaultPaymentMethod()));
    }
}
