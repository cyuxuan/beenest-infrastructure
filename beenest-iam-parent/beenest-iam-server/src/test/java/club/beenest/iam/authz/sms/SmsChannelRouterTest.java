package club.beenest.iam.authz.sms;

import club.beenest.iam.authz.config.IamSmsProperties;
import club.beenest.iam.core.spi.sms.SmsChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 短信通道路由测试。
 * <p>"通道可用"是准入链路上的一个事实判断:管理端据此允许把短信写进
 * 客户端白名单,登录页据此决定展不展示短信入口。判定偏宽松,就会做出
 * 一个点了必然报错的入口——那比不展示更糟。</p>
 */
class SmsChannelRouterTest {

    @Test
    @DisplayName("通道名未配置时不可用")
    void notConfigured() {
        IamSmsProperties properties = new IamSmsProperties();
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", true)), properties);

        assertTrue(router.resolve().isEmpty());
    }

    @Test
    @DisplayName("通道名匹配但配置不完整时不可用")
    void configuredButIncomplete() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", false)), properties);

        assertTrue(router.resolve().isEmpty());
    }

    @Test
    @DisplayName("通道名匹配且配置完整时可用")
    void configuredAndComplete() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", true)), properties);

        assertFalse(router.resolve().isEmpty());
    }

    /** 构造一个只关心"配置是否完整"的通道替身。 */
    private static SmsChannel channel(String id, boolean configured) {
        SmsChannel channel = mock(SmsChannel.class);
        when(channel.channelId()).thenReturn(id);
        when(channel.isConfigured()).thenReturn(configured);
        return channel;
    }

    @Test
    @DisplayName("阿里云通道:四项配置缺一即视为未就绪")
    void aliyunIncompleteConfig() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        properties.setAccessKeyId("ak");
        properties.setAccessKeySecret("sk");
        properties.setSignName("签名");
        // templateCode 故意留空
        SmsChannel channel = new AliyunSmsChannel(properties, new ObjectMapper());

        assertFalse(channel.isConfigured());
    }

    @Test
    @DisplayName("阿里云通道:四项配置齐全视为就绪")
    void aliyunCompleteConfig() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        properties.setAccessKeyId("ak");
        properties.setAccessKeySecret("sk");
        properties.setSignName("签名");
        properties.setTemplateCode("SMS_123");
        SmsChannel channel = new AliyunSmsChannel(properties, new ObjectMapper());

        assertTrue(channel.isConfigured());
    }
}
