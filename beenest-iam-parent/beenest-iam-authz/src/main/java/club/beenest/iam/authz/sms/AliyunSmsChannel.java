package club.beenest.iam.authz.sms;

import club.beenest.iam.authz.config.IamSmsProperties;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.sms.SmsChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信通道(dysmsapi 2017-05-25,RPC 风格签名)。
 * <p>按短信模板发送验证码,模板参数为 {@code {"code":"..."}};响应 Code 非 OK 时按发送失败处理。
 * 连接超时 3 秒,读取超时 5 秒。</p>
 */
public class AliyunSmsChannel implements SmsChannel {

    /** 阿里云短信 API 端点 */
    static final String ENDPOINT = "https://dysmsapi.aliyuncs.com";
    /** dysmsapi 版本(SendSms) */
    static final String API_VERSION = "2017-05-25";
    /** 连接/读取超时:短信链路不能拖住登录线程 */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final IamSmsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AliyunSmsChannel(IamSmsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String channelId() {
        return "aliyun";
    }

    @Override
    public void send(String phone, String code, String realmKey) {
        // 1. 组装请求:公共参数 + SendSms 业务参数(TreeMap 保证签名前有序)
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", properties.getAccessKeyId());
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("Version", API_VERSION);
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureVersion", "1.0");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("Timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        params.put("PhoneNumbers", phone);
        params.put("SignName", properties.getSignName());
        params.put("TemplateCode", properties.getTemplateCode());
        params.put("TemplateParam", "{\"code\":\"" + code + "\"}");
        // 2. 签名(阿里云 RPC 规范:HMAC-SHA1(key = secret + "&"))
        params.put("Signature", sign(params, properties.getAccessKeySecret()));
        // 3. 调用并判定响应码:非 OK 一律按发送失败处理,消息带阿里云原文便于运维定位
        String body;
        try {
            body = restClient.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED,
                    "短信通道调用失败(" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        if (!isOk(body)) {
            throw new BusinessException(ErrorCode.SMS_SEND_FAILED,
                    "短信通道返回错误: " + extractMessage(body));
        }
    }

    @Override
    public boolean isConfigured() {
        // 与 send() 读取的配置项一致
        return properties.isAliyunConfigured();
    }

    /**
     * 响应是否为成功:Code 为 OK 视为成功,无法解析时按失败处理。
     *
     * @param body 响应体
     * @return 是否成功
     */
    private boolean isOk(String body) {
        return "OK".equals(readCode(body));
    }

    /**
     * 读取响应中的 Code 字段。
     *
     * @param body 响应体
     * @return Code 值;响应为空或解析失败时返回 null
     */
    private String readCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("Code");
            return code == null || code.isNull() ? null : code.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取响应中的错误码与消息。
     *
     * @param body 响应体
     * @return 便于运维定位的错误描述
     */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "空响应";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node.get("Code");
            JsonNode message = node.get("Message");
            return (code == null ? "未知" : code.asText())
                    + "/" + (message == null ? "无消息" : message.asText());
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    // ===== 签名算法(阿里云 RPC 风格,纯函数便于单测固化) =====

    /**
     * 计算签名。
     * <p>规范:参数按 key 字典序拼接并做 RFC3986 编码,
     * {@code StringToSign = GET & %2F & <编码后的查询串>},
     * 密钥为 {@code AccessKeySecret + "&"}。</p>
     *
     * @param params          请求参数(不含 Signature)
     * @param accessKeySecret AccessKeySecret
     * @return Base64 编码的签名
     * @throws IllegalStateException 签名算法不可用或密钥非法
     */
    static String sign(Map<String, String> params, String accessKeySecret) {
        StringBuilder query = new StringBuilder();
        // TreeMap 保证与调用方传入顺序无关;阿里云要求按 key 字典序
        new TreeMap<>(params).forEach((key, value) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(percentEncode(key)).append('=').append(percentEncode(value));
        });
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(query.toString());
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA1 是 JDK 强制实现,密钥长度非法才可能到这里
            throw new IllegalStateException("短信通道签名失败", e);
        }
    }

    /**
     * RFC3986 编码。
     * <p>{@link URLEncoder} 按 form 规则把空格编成 {@code +},阿里云要求 {@code %20};
     * {@code * ~ ! ' ( )} 也被 URLEncoder 保留,需显式处理。</p>
     *
     * @param value 原始值
     * @return 编码结果
     */
    static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /**
     * 是否为 RFC3986 非保留字符。
     *
     * @param c 字节值
     * @return 字母、数字、-、_、.、~ 返回 true
     */
    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }
}
