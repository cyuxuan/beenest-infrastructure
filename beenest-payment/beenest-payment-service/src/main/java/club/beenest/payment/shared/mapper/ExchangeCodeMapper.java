package club.beenest.payment.shared.mapper;

import club.beenest.payment.shared.domain.entity.ExchangeCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 兑换码数据访问接口
 */
@Mapper
public interface ExchangeCodeMapper {

    /**
     * 根据兑换码查询
     * @param code 兑换码
     * @return 兑换码实体
     */
    ExchangeCode selectByCode(@Param("code") String code);

    /**
     * 根据主键ID查询
     * @param id 主键ID
     * @return 兑换码实体
     */
    ExchangeCode selectById(@Param("id") Long id);

    /**
     * 根据批次号查询兑换码列表
     * @param batchNo 批次号
     * @return 兑换码列表
     */
    List<ExchangeCode> selectByBatchNo(@Param("batchNo") String batchNo);

    /**
     * 查询所有兑换码（支持关键字搜索）
     * @param keyword 搜索关键字
     * @return 兑换码列表
     */
    List<ExchangeCode> selectAll(@Param("keyword") String keyword);

    /**
     * 新增兑换码
     * @param exchangeCode 兑换码实体
     * @return 影响行数
     */
    int insert(ExchangeCode exchangeCode);

    /**
     * 批量新增兑换码
     * @param list 兑换码列表
     * @return 影响行数
     */
    int batchInsert(@Param("list") List<ExchangeCode> list);

    /**
     * 更新兑换码
     * @param exchangeCode 兑换码实体
     * @return 影响行数
     */
    int update(ExchangeCode exchangeCode);

    /**
     * 标记兑换码为已使用
     * @param code 兑换码
     * @param usedBy 使用者ID
     * @return 影响行数
     */
    int markAsUsed(@Param("code") String code, @Param("usedBy") String usedBy);

    /**
     * 根据兑换码删除
     * @param code 兑换码
     * @return 影响行数
     */
    int deleteByCode(@Param("code") String code);

    /**
     * 根据批次号删除
     * @param batchNo 批次号
     * @return 影响行数
     */
    int deleteByBatchNo(@Param("batchNo") String batchNo);
}
