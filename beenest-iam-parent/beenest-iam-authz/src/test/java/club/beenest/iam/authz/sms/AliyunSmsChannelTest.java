package club.beenest.iam.authz.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阿里云短信通道签名算法单测。
 * <p>期望值由独立实现的 Python 脚本按阿里云 RPC 签名规范计算后固化,
 * 覆盖中文、JSON 花括号、冒号等特殊字符的 RFC3986 编码。</p>
 */
class AliyunSmsChannelTest {

    /** 固定参数的期望签名(与调用方传入 Map 的顺序无关,实现内按 key 排序)。 */
    private static final String EXPECTED_SIGNATURE = "30AmRaBWOwqMLjbSojuhZ+i5ndE=";

    private final AliyunSmsChannel channel = new AliyunSmsChannel(
            new club.beenest.iam.authz.config.IamSmsProperties(), new ObjectMapper());

    @Test
    @DisplayName("签名与阿里云规范一致(中文/JSON/冒号特殊字符编码)")
    void signatureMatchesAliyunSpec() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("AccessKeyId", "testid");
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("Version", "2017-05-25");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", "fixed-nonce");
        params.put("Timestamp", "2026-10-22T03:50:00Z");
        params.put("PhoneNumbers", "13800138000");
        params.put("SignName", "蜂巢身份");
        params.put("TemplateCode", "SMS_123456789");
        params.put("TemplateParam", "{\"code\":\"123456\"}");
        assertEquals(EXPECTED_SIGNATURE, AliyunSmsChannel.sign(params, "testsecret"));
    }

    @Test
    @DisplayName("percentEncode 按 RFC3986:空格 %20、非保留字符全编码、中文 UTF-8 编码")
    void percentEncodeFollowsRfc3986() {
        assertEquals("a%20b", AliyunSmsChannel.percentEncode("a b"));
        // 阿里云 unreserved 集只有字母数字与 -_.~,sub-delims(*!'() 等)同样必须编码
        assertEquals("%2A%21%27%28%29", AliyunSmsChannel.percentEncode("*!'()"));
        assertEquals("%E8%9C%82%E5%B7%A2", AliyunSmsChannel.percentEncode("蜂巢"));
        assertEquals("%2F", AliyunSmsChannel.percentEncode("/"));
    }

    @Test
    @DisplayName("通道标识固定 aliyun,与配置项对应")
    void channelIdIsAliyun() {
        assertEquals("aliyun", channel.channelId());
        assertTrue(channel.channelId().matches("[a-z]+"));
    }
}
