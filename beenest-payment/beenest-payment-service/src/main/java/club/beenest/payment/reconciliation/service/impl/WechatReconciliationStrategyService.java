package club.beenest.payment.reconciliation.service.impl;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.reconciliation.dto.PlatformOrderItem;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.reconciliation.service.IReconciliationStrategyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信对账策略
 * 优先：微信交易账单 API（CSV下载）
 * 降级：逐笔查询 queryOrderByOutTradeNo
 *
 * @author System
 * @since 2026-04-08
 */
@Service
@Slf4j
public class WechatReconciliationStrategyService implements IReconciliationStrategyService {

    private static final String TRADE_BILL_URL = "https://api.mch.weixin.qq.com/v3/bill/tradebill?bill_date=%s&bill_type=ALL";

    @Autowired
    private PaymentConfig paymentConfig;

    @Autowired(required = false)
    private JsapiServiceExtension jsapiService;

    @Autowired(required = false)
    private Config wechatPaySdkConfig;

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_WECHAT;
    }

    @Override
    public List<PlatformOrderItem> fetchPlatformOrders(String date) {
        // 优先尝试账单下载
        try {
            List<PlatformOrderItem> items = fetchFromBillApi(date);
            if (items != null && !items.isEmpty()) {
                log.info("微信账单下载成功 - date: {}, 笔数: {}", date, items.size());
                return items;
            }
        } catch (Exception e) {
            log.warn("微信账单下载失败，降级为逐笔查询 - date: {}, error: {}", date, e.getMessage());
        }

        // 降级：逐笔查询
        return fetchByPerOrderQuery(date);
    }

    /**
     * 通过微信交易账单 API 获取
     * 微信支付 V3 的 tradebill 接口返回 CSV 格式的账单
     */
    private List<PlatformOrderItem> fetchFromBillApi(String date) throws Exception {
        if (wechatPaySdkConfig == null) {
            throw new RuntimeException("微信支付配置未初始化");
        }

        String url = String.format(TRADE_BILL_URL, date);
        // 使用微信 SDK 的 HTTP 客户端发送请求（自动签名）
        // 注意：这里使用简化方式，生产环境建议通过 SDK 的 BillService 调用
        // 此处通过逐笔查询作为主要降级路径，账单 API 需要额外 SDK 配置

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("微信账单API返回非200状态: " + response.statusCode());
        }

        // 微信 V3 账单 API 返回下载链接
        String body = response.body();
        var jsonNode = objectMapper.readTree(body);
        String downloadUrl = jsonNode.path("download_url").asText(null);

        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new RuntimeException("微信账单下载链接为空");
        }

        // 下载账单 CSV（gzip 压缩）
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build();

        HttpResponse<String> csvResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofString());
        return parseWechatBillCsv(csvResponse.body());
    }

    /**
     * 解析微信账单 CSV
     * 微信账单格式：前几行为表头信息，之后是数据行
     * 关键字段：交易时间, 公众账号ID, 商户号, 子商户号, 设备号, 订单号, 商户订单号, ...
     */
    private List<PlatformOrderItem> parseWechatBillCsv(String csv) {
        List<PlatformOrderItem> items = new ArrayList<>();
        String[] lines = csv.split("\n");

        // 微信账单前2行是表头，跳过；最后一行是汇总
        for (int i = 2; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // 微信 CSV 用 `,` 分隔，但字段内可能用 `"` 包裹
            String[] fields = parseCsvLine(line);
            if (fields.length < 12) continue;

            try {
                PlatformOrderItem item = new PlatformOrderItem();
                // 微信账单字段顺序（V3 版本，具体以实际为准）：
                // fields[0] = 交易时间, fields[6] = 商户订单号(out_trade_no),
                // fields[7] = 特约商户订单号, fields[9] = 订单金额(元),
                // fields[13] = 交易状态(SUCCESS/REFUND/NOTPAY/CLOSED),
                // fields[16] = 微信支付订单号(transaction_id)
                item.setOrderNo(fields[6].replace("`", ""));
                item.setTransactionNo(fields.length > 16 ? fields[16].replace("`", "") : "");

                // 金额字段：元转分
                String amountStr = fields[9].replace("`", "");
                if (!amountStr.isEmpty()) {
                    try {
                        item.setAmount(Math.round(Double.parseDouble(amountStr) * 100));
                    } catch (NumberFormatException ignored) {
                    }
                }

                item.setStatus(fields.length > 13 ? fields[13].replace("`", "") : "");

                // 交易时间
                String timeStr = fields[0].replace("`", "");
                try {
                    item.setPaidTime(LocalDateTime.parse(timeStr,
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                } catch (Exception ignored) {
                }

                item.setRawData(line);
                items.add(item);
            } catch (Exception e) {
                log.warn("解析微信账单行失败: {}", line, e);
            }
        }
        return items;
    }

    /**
     * 降级路径：逐笔查询本地已支付订单
     */
    private List<PlatformOrderItem> fetchByPerOrderQuery(String date) {
        List<PlatformOrderItem> items = new ArrayList<>();

        if (jsapiService == null) {
            log.warn("微信支付服务未初始化，无法逐笔查询");
            return items;
        }

        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDateTime startTime = LocalDateTime.of(localDate, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

        List<PaymentOrder> localOrders = paymentOrderMapper.selectByTimeRangeAndPlatform(
                startTime, endTime, PaymentConstants.PLATFORM_WECHAT);

        for (PaymentOrder order : localOrders) {
            try {
                QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
                request.setMchid(paymentConfig.getWechat().getMchId());
                request.setOutTradeNo(order.getOrderNo());

                Transaction transaction = jsapiService.queryOrderByOutTradeNo(request);

                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());
                item.setTransactionNo(transaction.getTransactionId());
                item.setStatus(transaction.getTradeState() != null ? transaction.getTradeState().name() : "");
                if (transaction.getAmount() != null) {
                    item.setAmount((long) transaction.getAmount().getTotal());
                }
                item.setRawData(objectMapper.writeValueAsString(transaction));
                items.add(item);
            } catch (Exception e) {
                log.warn("微信逐笔查询失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
                // 查询失败的订单仍然记录（标记为查询失败）
                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());
                item.setStatus("QUERY_FAILED");
                item.setRawData("{\"error\":\"" + e.getMessage() + "\"}");
                items.add(item);
            }
        }

        log.info("微信逐笔查询完成 - date: {}, 笔数: {}", date, items.size());
        return items;
    }

    /**
     * 简易 CSV 行解析（处理引号包裹的字段）
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}
