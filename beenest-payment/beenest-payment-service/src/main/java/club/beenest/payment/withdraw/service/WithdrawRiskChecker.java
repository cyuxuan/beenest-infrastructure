package club.beenest.payment.withdraw.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.shared.domain.entity.RiskRule;
import club.beenest.payment.shared.service.IRiskRuleService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * 提现风控检查服务
 * 检测异常提现行为，降低资金风险
 */
@Service
@Slf4j
public class WithdrawRiskChecker {

    @Autowired
    private IRiskRuleService riskRuleService;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;  // kept name for compatibility

    private static final String KEY_PREFIX_FREQUENCY = "risk:frequency:";

    /**
     * 检查是否为可疑提现
     *
     * @param customerNo 用户编号
     * @param request 提现请求
     * @return 风控检查结果
     */
    public RiskCheckResult check(String customerNo, WithdrawRequestDTO request) {
        log.info("开始风控检查 - customerNo: {}, amount: {}", customerNo, request.getAmount());

        List<RiskRule> rules = riskRuleService.getRules();
        for (RiskRule rule : rules) {
            if (!rule.getIsEnable()) continue;

            // 1. LIMIT (限额)
            if ("LIMIT".equals(rule.getRuleType())) {
                if (request.getAmount() > rule.getThreshold()) {
                    log.warn("触发风控规则 [限额] - rule: {}, amount: {}", rule.getRuleName(), request.getAmount());
                    return buildResult(rule.getAction(), "超过单笔提现限额");
                }
            }

            // 2. FREQUENCY (频控)
            if ("FREQUENCY".equals(rule.getRuleType())) {
                String key = KEY_PREFIX_FREQUENCY + customerNo;
                if (stringRedisTemplate != null) {
                    try {
                        Long count = stringRedisTemplate.opsForValue().increment(key);
                        if (count != null && count == 1) {
                            stringRedisTemplate.expire(key, Duration.ofSeconds(rule.getTimeWindow()));
                        }
                        if (count != null && count > rule.getThreshold()) {
                            log.warn("触发风控规则 [频控] - rule: {}, count: {}", rule.getRuleName(), count);
                            return buildResult(rule.getAction(), "提现过于频繁");
                        }
                    } catch (Exception e) {
                        log.error("风控频控检查异常 - Redis不可用，拒绝提现请求以保证资金安全", e);
                        // 【资金安全关键】Redis异常时拒绝而非放行，防止风控失效导致资金损失
                        return RiskCheckResult.reject("风控服务暂时不可用，请稍后再试");
                    }
                } else {
                    // Redis 未配置时拒绝提现，与 Redis 异常时保持一致的安全策略
                    log.error("Redis未配置，频控检查无法执行，拒绝提现请求以保证资金安全");
                    return RiskCheckResult.reject("风控服务未就绪，请稍后再试");
                }
            }

            // 3. BLACKLIST (黑名单)
            if ("BLACKLIST".equals(rule.getRuleType())) {
                // If customerNo matches blacklist pattern (stored in threshold or special field?)
                // Assuming ruleName or code might contain the ID, or we need a Blacklist table.
                // For now, skip.
            }
        }

        // Keep existing hardcoded checks as fallback or remove them if fully rule-based
        if (isAbnormalTime()) {
             return RiskCheckResult.warning("深夜提现，需要人工审核");
        }

        return RiskCheckResult.pass();
    }

    private RiskCheckResult buildResult(String action, String message) {
        if ("REJECT".equals(action)) {
            return RiskCheckResult.reject(message);
        } else if ("REVIEW".equals(action)) {
            return RiskCheckResult.warning(message + "，转入人工审核");
        } else {
            // ALERT
            log.warn("风控告警: {}", message);
            return RiskCheckResult.pass();
        }
    }

    private boolean isAbnormalTime() {
        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(23, 0)) || now.isBefore(LocalTime.of(6, 0));
    }

    /**
     * 风控检查结果
     */
    @Data
    public static class RiskCheckResult {
        private boolean pass;
        private boolean needManualReview;
        private String message;

        public static RiskCheckResult pass() {
            RiskCheckResult result = new RiskCheckResult();
            result.pass = true;
            result.needManualReview = false;
            return result;
        }

        public static RiskCheckResult warning(String message) {
            RiskCheckResult result = new RiskCheckResult();
            result.pass = true;
            result.needManualReview = true;
            result.message = message;
            return result;
        }

        public static RiskCheckResult reject(String message) {
            RiskCheckResult result = new RiskCheckResult();
            result.pass = false;
            result.needManualReview = false;
            result.message = message;
            return result;
        }
    }
}
