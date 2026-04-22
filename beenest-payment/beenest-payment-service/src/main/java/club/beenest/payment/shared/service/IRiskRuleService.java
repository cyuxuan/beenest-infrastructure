package club.beenest.payment.shared.service;

import club.beenest.payment.shared.domain.entity.RiskRule;
import java.util.List;

/**
 * 风控规则服务接口
 * 提供风控规则的增删改查功能
 *
 * <p>风控规则用于在支付、提现等关键操作前进行风险检查，
 * 支持限额控制、频率限制、黑名单等规则类型。</p>
 *
 * @author System
 * @since 2026-02-11
 */
public interface IRiskRuleService {

    /**
     * 查询所有风控规则
     *
     * @return 风控规则列表
     */
    List<RiskRule> getRules();

    /**
     * 创建风控规则
     *
     * @param rule 风控规则参数，ruleCode、ruleName、ruleType、threshold、action 不能为空
     * @throws IllegalArgumentException 如果必填参数为空
     */
    void createRule(RiskRule rule);

    /**
     * 更新风控规则
     *
     * @param rule 风控规则参数，id 不能为空
     * @throws IllegalArgumentException 如果 id 为空
     */
    void updateRule(RiskRule rule);

    /**
     * 删除风控规则
     *
     * @param id 规则ID，不能为空
     * @throws IllegalArgumentException 如果 id 为空
     */
    void deleteRule(Long id);
}
