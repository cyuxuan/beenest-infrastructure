package club.beenest.payment.reconciliation.service.impl;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.reconciliation.dto.PlatformOrderItem;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.reconciliation.service.IReconciliationStrategyService;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayDataDataserviceBillDownloadurlQueryModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayDataDataserviceBillDownloadurlQueryRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayDataDataserviceBillDownloadurlQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.math.BigDecimal;
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
import java.util.zip.ZipInputStream;

/**
 * 支付宝对账策略
 * 优先：支付宝账单下载 API（CSV下载）
 * 降级：逐笔查询 AlipayTradeQuery
 *
 * @author System
 * @since 2026-04-08
 */
@Service
@Slf4j
public class AlipayReconciliationStrategyService implements IReconciliationStrategyService {

    @Autowired
    private PaymentConfig paymentConfig;

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_ALIPAY;
    }

    @Override
    public List<PlatformOrderItem> fetchPlatformOrders(String date) {
        // 优先尝试账单下载
        try {
            List<PlatformOrderItem> items = fetchFromBillApi(date);
            if (items != null && !items.isEmpty()) {
                log.info("支付宝账单下载成功 - date: {}, 笔数: {}", date, items.size());
                return items;
            }
        } catch (Exception e) {
            log.warn("支付宝账单下载失败，降级为逐笔查询 - date: {}, error: {}", date, e.getMessage());
        }

        // 降级：逐笔查询
        return fetchByPerOrderQuery(date);
    }

    /**
     * 通过支付宝账单下载 API 获取
     * 调用 alipay.data.dataservice.bill.downloadurl.query 获取账单下载链接
     * 下载 ZIP 压缩包后解压得到 CSV
     */
    private List<PlatformOrderItem> fetchFromBillApi(String date) throws Exception {
        PaymentConfig.AlipayConfig config = paymentConfig.getAlipay();
        if (!config.isEnabled()) {
            throw new RuntimeException("支付宝未启用");
        }

        AlipayClient client = new DefaultAlipayClient(
                config.getGatewayUrl(), config.getAppId(), config.getPrivateKey(),
                config.getFormat(), config.getCharset(), config.getAlipayPublicKey(), config.getSignType());

        AlipayDataDataserviceBillDownloadurlQueryRequest request = new AlipayDataDataserviceBillDownloadurlQueryRequest();
        AlipayDataDataserviceBillDownloadurlQueryModel model = new AlipayDataDataserviceBillDownloadurlQueryModel();
        model.setBillType("trade");
        model.setBillDate(date);
        request.setBizModel(model);

        AlipayDataDataserviceBillDownloadurlQueryResponse response = client.execute(request);
        if (!response.isSuccess()) {
            throw new RuntimeException("支付宝账单查询失败: " + response.getSubMsg());
        }

        String downloadUrl = response.getBillDownloadUrl();
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new RuntimeException("支付宝账单下载链接为空");
        }

        // 下载 ZIP 文件并解压
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build();

        HttpResponse<byte[]> zipResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        return parseAlipayBillZip(zipResponse.body());
    }

    /**
     * 解析支付宝账单 ZIP（内含 CSV 文件）
     * 支付宝账单 ZIP 包含两个 CSV 文件：账单明细和汇总
     * 明细文件名格式：alipay_app_..._明细(账单).csv
     */
    private List<PlatformOrderItem> parseAlipayBillZip(byte[] zipData) throws Exception {
        List<PlatformOrderItem> items = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                // 查找明细文件（跳过汇总文件）
                String entryName = entry.getName();
                if (entryName.contains("明细") || entryName.contains("details")) {
                    // 读取 CSV 内容
                    StringBuilder sb = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(zis, "GBK"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    items.addAll(parseAlipayBillCsv(sb.toString()));
                }
                zis.closeEntry();
                entry = zis.getNextEntry();
            }
        }
        return items;
    }

    /**
     * 解析支付宝账单 CSV
     * 支付宝账单格式：前几行为表头信息，之后是数据行，最后是汇总行
     * 关键字段：支付宝交易号, 商户订单号, 交易创建时间, 交易付款时间, ...
     *           最近修改时间, 交易来源, 业务类型, 商品名称, ...
     *           交易金额(元), ... , 交易状态
     */
    private List<PlatformOrderItem> parseAlipayBillCsv(String csv) {
        List<PlatformOrderItem> items = new ArrayList<>();
        String[] lines = csv.split("\n");

        // 支付宝账单前若干行是表头信息（以 # 开头），跳过
        // 数据行以 "支付宝交易号," 开头或直接是 CSV 数据
        // 汇总行通常以 "# 汇总" 或类似标记开头
        int dataStartIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // 跳过空行和表头行（# 开头）
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // 第一行非 # 行是列标题，下一行开始是数据
            if (dataStartIndex == -1) {
                dataStartIndex = i + 1;
                continue;
            }
        }

        if (dataStartIndex == -1) {
            log.warn("支付宝账单未找到数据行");
            return items;
        }

        for (int i = dataStartIndex; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] fields = parseCsvLine(line);
            if (fields.length < 16) continue;

            try {
                // 支付宝账单字段顺序（V2 版本，具体以实际为准）：
                // fields[0] = 支付宝交易号(trade_no),
                // fields[1] = 商户订单号(out_trade_no),
                // fields[5] = 交易创建时间,
                // fields[6] = 付款时间,
                // fields[9] = 交易金额(元),
                // fields[15] = 交易状态(TRADE_SUCCESS/TRADE_FINISHED/...)
                // 注意：字段顺序可能因账单类型不同而变化

                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(fields[1].replace("`", "").trim());
                item.setTransactionNo(fields[0].replace("`", "").trim());

                // 金额字段：元转分
                String amountStr = fields.length > 9 ? fields[9].replace("`", "").trim() : "";
                if (!amountStr.isEmpty()) {
                    try {
                        item.setAmount(new BigDecimal(amountStr).multiply(new BigDecimal(100)).longValue());
                    } catch (NumberFormatException ignored) {
                    }
                }

                // 交易状态
                String status = fields.length > 15 ? fields[15].replace("`", "").trim() : "";
                item.setStatus(status);

                // 付款时间
                String timeStr = fields.length > 6 ? fields[6].replace("`", "").trim() : "";
                if (timeStr.isEmpty()) {
                    timeStr = fields.length > 5 ? fields[5].replace("`", "").trim() : "";
                }
                if (!timeStr.isEmpty()) {
                    try {
                        item.setPaidTime(LocalDateTime.parse(timeStr,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    } catch (Exception ignored) {
                    }
                }

                item.setRawData(line);
                items.add(item);
            } catch (Exception e) {
                log.warn("解析支付宝账单行失败: {}", line, e);
            }
        }
        return items;
    }

    /**
     * 降级路径：逐笔查询本地已支付订单
     */
    private List<PlatformOrderItem> fetchByPerOrderQuery(String date) {
        List<PlatformOrderItem> items = new ArrayList<>();

        PaymentConfig.AlipayConfig config = paymentConfig.getAlipay();
        if (!config.isEnabled()) {
            log.warn("支付宝未启用，无法逐笔查询");
            return items;
        }

        AlipayClient client;
        try {
            client = new DefaultAlipayClient(
                    config.getGatewayUrl(), config.getAppId(), config.getPrivateKey(),
                    config.getFormat(), config.getCharset(), config.getAlipayPublicKey(), config.getSignType());
        } catch (Exception e) {
            log.error("支付宝客户端创建失败", e);
            return items;
        }

        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDateTime startTime = LocalDateTime.of(localDate, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

        List<PaymentOrder> localOrders = paymentOrderMapper.selectByTimeRangeAndPlatform(
                startTime, endTime, PaymentConstants.PLATFORM_ALIPAY);

        for (PaymentOrder order : localOrders) {
            try {
                AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
                AlipayTradeQueryModel model = new AlipayTradeQueryModel();
                model.setOutTradeNo(order.getOrderNo());
                request.setBizModel(model);

                AlipayTradeQueryResponse response = client.execute(request);

                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());

                if (response.isSuccess()) {
                    item.setTransactionNo(response.getTradeNo());
                    item.setStatus(response.getTradeStatus());
                    if (response.getTotalAmount() != null) {
                        item.setAmount(new BigDecimal(response.getTotalAmount())
                                .multiply(new BigDecimal(100)).longValue());
                    }
                    item.setRawData(objectMapper.writeValueAsString(response));
                } else {
                    item.setStatus("QUERY_FAILED");
                    item.setRawData("{\"subCode\":\"" + response.getSubCode()
                            + "\",\"subMsg\":\"" + response.getSubMsg() + "\"}");
                }
                items.add(item);
            } catch (Exception e) {
                log.warn("支付宝逐笔查询失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());
                item.setStatus("QUERY_FAILED");
                item.setRawData("{\"error\":\"" + e.getMessage() + "\"}");
                items.add(item);
            }
        }

        log.info("支付宝逐笔查询完成 - date: {}, 笔数: {}", date, items.size());
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
