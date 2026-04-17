package club.beenest.payment.service.impl;

import club.beenest.payment.config.ReconciliationStrategyFactory;
import club.beenest.payment.mapper.PaymentOrderMapper;
import club.beenest.payment.mapper.ReconciliationTaskMapper;
import club.beenest.payment.object.dto.PlatformOrderItem;
import club.beenest.payment.object.dto.ReconciliationDetailDTO;
import club.beenest.payment.object.dto.ReconciliationQueryDTO;
import club.beenest.payment.object.dto.ReconciliationResultDTO;
import club.beenest.payment.object.entity.PaymentOrder;
import club.beenest.payment.object.entity.ReconciliationTask;
import club.beenest.payment.service.IReconciliationService;
import club.beenest.payment.service.IReconciliationStrategyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账服务实现
 *
 * <p>双边对账：本地订单 vs 第三方平台账单逐条比对。</p>
 * <p>通过策略工厂获取对应平台的对账策略，优先账单下载，降级逐笔查询。</p>
 */
@Service
@Slf4j
public class ReconciliationServiceImpl implements IReconciliationService {

    @Autowired
    private ReconciliationTaskMapper reconciliationTaskMapper;

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    @Autowired
    private ReconciliationStrategyFactory strategyFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Page<ReconciliationTask> queryTasks(ReconciliationQueryDTO query, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return (Page<ReconciliationTask>) reconciliationTaskMapper.selectByQuery(
                query.getDate(), query.getChannel(), query.getStatus());
    }

    @Override
    public void createTask(String date, String channel) {
        log.info("开始创建对账任务 - date: {}, channel: {}", date, channel);

        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("对账日期不能为空");
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("支付渠道不能为空");
        }
        try {
            LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式不正确，应为 yyyy-MM-dd: " + date);
        }

        if (!strategyFactory.isSupported(channel)) {
            throw new IllegalArgumentException("不支持的对账平台: " + channel);
        }

        ReconciliationTask task = new ReconciliationTask();
        task.setDate(date);
        task.setChannel(channel);
        task.setStatus("PROCESSING");
        task.setTotalOrders(0);
        task.setTotalAmount(0L);

        reconciliationTaskMapper.insert(task);

        try {
            processReconciliation(task, date, channel);
        } catch (Exception e) {
            log.error("对账任务处理失败 - taskId: {}", task.getId(), e);
            task.setStatus("FAILED");
            reconciliationTaskMapper.update(task);
        }
    }

    /**
     * 执行双边对账
     * 1. 收集本地订单
     * 2. 获取第三方平台账单（通过策略）
     * 3. 逐条比对
     * 4. 更新对账任务结果
     */
    private void processReconciliation(ReconciliationTask task, String date, String channel) {
        log.info("执行双边对账 - taskId: {}, date: {}, channel: {}", task.getId(), date, channel);

        // 1. 收集本地订单（只统计已支付的）
        LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        LocalDateTime startTime = LocalDateTime.of(localDate, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

        List<PaymentOrder> localOrders = paymentOrderMapper.selectByTimeRangeAndPlatform(
                startTime, endTime, channel);
        List<PaymentOrder> paidOrders = localOrders.stream()
                .filter(o -> "PAID".equals(o.getStatus()))
                .collect(Collectors.toList());

        // 2. 获取平台侧数据
        IReconciliationStrategyService strategy = strategyFactory.getStrategy(channel);
        List<PlatformOrderItem> platformItems = strategy.fetchPlatformOrders(date);

        // 3. 双边比对
        ReconciliationResultDTO result = doReconcile(paidOrders, platformItems);

        // 4. 更新对账任务
        task.setTotalOrders(result.getLocalCount());
        task.setTotalAmount(result.getLocalAmount());
        task.setPlatformOrderCount(result.getPlatformCount());
        task.setPlatformAmount(result.getPlatformAmount());
        task.setMatchCount(result.getMatchCount());
        task.setMismatchCount(result.getMismatchCount());

        if (result.getMismatchCount() > 0) {
            task.setStatus("MISMATCH");
        } else {
            task.setStatus("COMPLETED");
        }

        // 序列化差异明细
        if (!result.getDetails().isEmpty()) {
            try {
                task.setDetail(objectMapper.writeValueAsString(result.getDetails()));
            } catch (Exception e) {
                log.warn("序列化对账明细失败", e);
                task.setDetail("[]");
            }
        } else {
            task.setDetail("[]");
        }

        reconciliationTaskMapper.update(task);

        log.info("双边对账完成 - taskId: {}, 本地: {}笔/{}分, 平台: {}笔/{}分, 匹配: {}, 不匹配: {}",
                task.getId(), result.getLocalCount(), result.getLocalAmount(),
                result.getPlatformCount(), result.getPlatformAmount(),
                result.getMatchCount(), result.getMismatchCount());
    }

    /**
     * 执行双边比对逻辑
     * - 按 orderNo 匹配
     * - 匹配：金额+状态一致 → matchCount++
     * - 不匹配：金额/状态差异 → 记录明细
     * - 本地多出（平台无记录）→ LOCAL_ONLY
     * - 平台多出（本地无记录）→ PLATFORM_ONLY
     */
    private ReconciliationResultDTO doReconcile(List<PaymentOrder> localOrders,
                                                 List<PlatformOrderItem> platformItems) {
        ReconciliationResultDTO result = new ReconciliationResultDTO();

        // 本地统计
        result.setLocalCount(localOrders.size());
        result.setLocalAmount(localOrders.stream()
                .mapToLong(PaymentOrder::getAmount).sum());

        // 平台统计
        result.setPlatformCount(platformItems.size());
        result.setPlatformAmount(platformItems.stream()
                .filter(p -> p.getAmount() != null)
                .mapToLong(PlatformOrderItem::getAmount).sum());

        // 构建 Map 以便快速查找
        Map<String, PaymentOrder> localMap = localOrders.stream()
                .collect(Collectors.toMap(PaymentOrder::getOrderNo, Function.identity(), (a, b) -> a));
        Map<String, PlatformOrderItem> platformMap = platformItems.stream()
                .filter(p -> p.getOrderNo() != null && !p.getOrderNo().isEmpty())
                .collect(Collectors.toMap(PlatformOrderItem::getOrderNo, Function.identity(), (a, b) -> a));

        Set<String> allOrderNos = new HashSet<>();
        allOrderNos.addAll(localMap.keySet());
        allOrderNos.addAll(platformMap.keySet());

        for (String orderNo : allOrderNos) {
            PaymentOrder local = localMap.get(orderNo);
            PlatformOrderItem platform = platformMap.get(orderNo);

            if (local != null && platform != null) {
                // 双边都有 → 比对金额和状态
                boolean amountMatch = (platform.getAmount() == null || local.getAmount().equals(platform.getAmount()));
                boolean statusMatch = isStatusMatch(local.getStatus(), platform.getStatus());

                if (amountMatch && statusMatch) {
                    result.setMatchCount(result.getMatchCount() + 1);
                } else {
                    result.setMismatchCount(result.getMismatchCount() + 1);
                    ReconciliationDetailDTO detail = new ReconciliationDetailDTO();
                    detail.setOrderNo(orderNo);
                    detail.setTransactionNo(platform.getTransactionNo());
                    detail.setLocalAmount(local.getAmount());
                    detail.setPlatformAmount(platform.getAmount());
                    detail.setLocalStatus(local.getStatus());
                    detail.setPlatformStatus(platform.getStatus());

                    if (!amountMatch && !statusMatch) {
                        detail.setType("AMOUNT_STATUS_MISMATCH");
                        detail.setDescription("金额和状态不一致");
                    } else if (!amountMatch) {
                        detail.setType("AMOUNT_MISMATCH");
                        detail.setDescription("金额不一致: 本地=" + local.getAmount() + "分, 平台=" + platform.getAmount() + "分");
                    } else {
                        detail.setType("STATUS_MISMATCH");
                        detail.setDescription("状态不一致: 本地=" + local.getStatus() + ", 平台=" + platform.getStatus());
                    }
                    result.getDetails().add(detail);
                }
            } else if (local != null) {
                // 仅本地有 → LOCAL_ONLY
                result.setMismatchCount(result.getMismatchCount() + 1);
                ReconciliationDetailDTO detail = new ReconciliationDetailDTO();
                detail.setOrderNo(orderNo);
                detail.setType("LOCAL_ONLY");
                detail.setLocalAmount(local.getAmount());
                detail.setLocalStatus(local.getStatus());
                detail.setDescription("仅本地有记录，平台无对应订单");
                result.getDetails().add(detail);
            } else if (platform != null) {
                // 仅平台有 → PLATFORM_ONLY
                result.setMismatchCount(result.getMismatchCount() + 1);
                ReconciliationDetailDTO detail = new ReconciliationDetailDTO();
                detail.setOrderNo(orderNo);
                detail.setType("PLATFORM_ONLY");
                detail.setTransactionNo(platform.getTransactionNo());
                detail.setPlatformAmount(platform.getAmount());
                detail.setPlatformStatus(platform.getStatus());
                detail.setDescription("仅平台有记录，本地无对应订单");
                result.getDetails().add(detail);
            }
        }

        return result;
    }

    /**
     * 判断本地状态与平台状态是否匹配
     * 各平台状态映射关系：
     * - 微信 SUCCESS ↔ PAID
     * - 支付宝 TRADE_SUCCESS/TRADE_FINISHED ↔ PAID
     * - 抖音 SUCCESS ↔ PAID
     * - QUERY_FAILED 视为查询失败，不影响匹配判断（但也不会匹配）
     */
    private boolean isStatusMatch(String localStatus, String platformStatus) {
        if (platformStatus == null || platformStatus.isEmpty() || "QUERY_FAILED".equals(platformStatus)) {
            return false;
        }
        if ("PAID".equals(localStatus)) {
            return switch (platformStatus.toUpperCase()) {
                case "SUCCESS", "TRADE_SUCCESS", "TRADE_FINISHED", "PAY_SUCCESS", "PAID" -> true;
                default -> false;
            };
        }
        return localStatus.equalsIgnoreCase(platformStatus);
    }
}
