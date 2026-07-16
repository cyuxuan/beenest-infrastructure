package club.beenest.payment.shared.mapper;

import club.beenest.payment.shared.domain.entity.AppCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用凭证数据访问接口
 * 提供应用凭证的数据库操作方法
 *
 * @author System
 * @since 2026-07-16
 */
@Mapper
public interface AppCredentialMapper {

    /**
     * 根据 app_id 查询凭证
     *
     * @param appId 业务系统标识
     * @return 凭证实体，不存在返回 null
     */
    AppCredential selectByAppId(@Param("appId") String appId);

    /**
     * 查询所有凭证
     *
     * @return 凭证列表
     */
    List<AppCredential> selectAll();

    /**
     * 根据状态查询凭证
     *
     * @param status 状态（ACTIVE/DISABLED）
     * @return 凭证列表
     */
    List<AppCredential> selectByStatus(@Param("status") String status);

    /**
     * 插入新的应用凭证
     *
     * @param credential 凭证实体
     * @return 影响行数
     */
    int insert(AppCredential credential);

    /**
     * 根据 app_id 更新凭证信息
     *
     * @param credential 凭证实体（appId 不可修改）
     * @return 影响行数
     */
    int updateByAppId(AppCredential credential);

    /**
     * 根据 app_id 更新状态
     *
     * @param appId  业务系统标识
     * @param status 目标状态
     * @return 影响行数
     */
    int updateStatus(@Param("appId") String appId, @Param("status") String status);
}
