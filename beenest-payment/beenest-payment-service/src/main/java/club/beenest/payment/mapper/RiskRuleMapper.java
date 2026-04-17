package club.beenest.payment.mapper;

import club.beenest.payment.object.entity.RiskRule;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 风控规则Mapper
 */
@Mapper
public interface RiskRuleMapper {

    /**
     * 新增风控规则
     * @param rule 风控规则实体
     * @return 影响行数
     */
    int insert(RiskRule rule);

    /**
     * 更新风控规则
     * @param rule 风控规则实体
     * @return 影响行数
     */
    int update(RiskRule rule);

    /**
     * 删除风控规则
     * @param id 规则ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 查询所有风控规则
     * @return 风控规则列表
     */
    List<RiskRule> selectAll();

    /**
     * 根据规则代码查询
     * @param ruleCode 规则代码
     * @return 风控规则实体
     */
    RiskRule selectByCode(String ruleCode);
}
