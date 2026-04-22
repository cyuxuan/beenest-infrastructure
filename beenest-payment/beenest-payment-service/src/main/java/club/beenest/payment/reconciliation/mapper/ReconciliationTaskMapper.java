package club.beenest.payment.reconciliation.mapper;

import club.beenest.payment.reconciliation.domain.entity.ReconciliationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账任务Mapper
 */
@Mapper
public interface ReconciliationTaskMapper {

    /**
     * 新增对账任务
     * @param task 对账任务实体
     * @return 影响行数
     */
    int insert(ReconciliationTask task);

    /**
     * 更新对账任务
     * @param task 对账任务实体
     * @return 影响行数
     */
    int update(ReconciliationTask task);

    /**
     * 查询对账任务列表
     * @param date 日期
     * @param channel 渠道
     * @param status 状态
     * @return 对账任务列表
     */
    List<ReconciliationTask> selectByQuery(@Param("date") String date, @Param("channel") String channel, @Param("status") String status);

    /**
     * 根据ID查询对账任务
     * @param id 任务ID
     * @return 对账任务实体
     */
    ReconciliationTask selectById(Long id);
}
