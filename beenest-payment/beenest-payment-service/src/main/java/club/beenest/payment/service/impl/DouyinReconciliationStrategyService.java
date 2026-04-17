package club.beenest.payment.service.impl;

import club.beenest.payment.constant.PaymentConstants;
import club.beenest.payment.mapper.PaymentOrderMapper;
import club.beenest.payment.object.dto.PlatformOrderItem;
import club.beenest.payment.object.entity.PaymentOrder;
import club.beenest.payment.service.IReconciliationStrategyService;
import club.beenest.payment.strategy.impl.DouyinPaymentStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 抖音对账策略
 * 仅支持逐笔查询（抖音无公开账单下载 API）
 *
 * @author System
 * @since 2026-04-08
 */
@Service
@Slf4j
public class DouyinReconciliationStrategyService implements IReconciliationStrategyService {

    @Autowired
    private DouyinPaymentStrategy douyinPaymentStrategy;

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getPlatform() {
        return PaymentConstants.PLATFORM_DOUYIN;
    }

    @Override
    public List<PlatformOrderItem> fetchPlatformOrders(String date) {
        // 抖音无公开账单下载 API，直接使用逐笔查询
        return fetchByPerOrderQuery(date);
    }

    /**
     * 逐笔查询本地已支付订单
     */
    private List<PlatformOrderItem> fetchByPerOrderQuery(String date) {
        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDateTime startTime = LocalDateTime.of(localDate, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

        List<PaymentOrder> localOrders = paymentOrderMapper.selectByTimeRangeAndPlatform(
                startTime, endTime, PaymentConstants.PLATFORM_DOUYIN);

        java.util.ArrayList<PlatformOrderItem> items = new java.util.ArrayList<>();

        for (PaymentOrder order : localOrders) {
            try {
                Map<String, Object> result = douyinPaymentStrategy.queryPayment(order);

                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());
                item.setStatus(result.get("platformStatus") != null
                        ? result.get("platformStatus").toString() : "");
                item.setTransactionNo(result.get("transactionNo") != null
                        ? result.get("transactionNo").toString() : "");
                if (result.get("amount") != null) {
                    item.setAmount(Long.parseLong(result.get("amount").toString()));
                }
                item.setRawData(objectMapper.writeValueAsString(result));
                items.add(item);
            } catch (Exception e) {
                log.warn("抖音逐笔查询失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
                PlatformOrderItem item = new PlatformOrderItem();
                item.setOrderNo(order.getOrderNo());
                item.setStatus("QUERY_FAILED");
                item.setRawData("{\"error\":\"" + e.getMessage() + "\"}");
                items.add(item);
            }
        }

        log.info("抖音逐笔查询完成 - date: {}, 笔数: {}", date, items.size());
        return items;
    }
}
