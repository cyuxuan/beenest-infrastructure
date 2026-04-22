package club.beenest.payment.shared.service.impl;

import club.beenest.payment.shared.mapper.RiskRuleMapper;
import club.beenest.payment.shared.domain.entity.RiskRule;
import club.beenest.payment.shared.service.IRiskRuleService;
import club.beenest.payment.util.PaymentValidateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 风控规则服务实现类
 *
 * @author System
 * @since 2026-02-11
 */
@Service
public class RiskRuleServiceImpl implements IRiskRuleService {

    @Autowired
    private RiskRuleMapper riskRuleMapper;

    @Override
    public List<RiskRule> getRules() {
        return riskRuleMapper.selectAll();
    }

    @Override
    public void createRule(RiskRule rule) {
        PaymentValidateUtils.notNull(rule, "风控规则不能为空");
        PaymentValidateUtils.notBlank(rule.getRuleCode(), "规则代码不能为空");
        PaymentValidateUtils.notBlank(rule.getRuleName(), "规则名称不能为空");
        PaymentValidateUtils.notBlank(rule.getRuleType(), "规则类型不能为空");
        PaymentValidateUtils.notNull(rule.getThreshold(), "阈值不能为空");
        PaymentValidateUtils.notBlank(rule.getAction(), "触发动作不能为空");
        riskRuleMapper.insert(rule);
    }

    @Override
    public void updateRule(RiskRule rule) {
        PaymentValidateUtils.notNull(rule, "风控规则不能为空");
        PaymentValidateUtils.notNull(rule.getId(), "规则ID不能为空");
        riskRuleMapper.update(rule);
    }

    @Override
    public void deleteRule(Long id) {
        PaymentValidateUtils.notNull(id, "规则ID不能为空");
        riskRuleMapper.deleteById(id);
    }
}
