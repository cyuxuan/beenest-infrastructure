package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.LogoutBroadcast;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 单点登出广播记录 Mapper(iam_logout_broadcast 单表)。
 * <p>每个客户端回调端点一条投递记录,状态初始为 PENDING;查询排除已逻辑删除记录。</p>
 */
public interface LogoutBroadcastMapper {

    /**
     * 插入广播记录,state 为空时写入 PENDING。
     *
     * @param broadcast 广播记录实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(LogoutBroadcast broadcast);

    /**
     * 回写投递结果。
     *
     * @param id           广播记录主键
     * @param state        投递状态
     * @param httpStatus   对端返回的 HTTP 状态码
     * @param errorMessage 失败信息
     * @return 受影响行数
     */
    int updateResult(@Param("id") Long id, @Param("state") String state,
                     @Param("httpStatus") Integer httpStatus,
                     @Param("errorMessage") String errorMessage);

    /**
     * 按域与会话标识列出未删除广播记录,按主键升序。
     *
     * @param realmId 身份域主键
     * @param sid     会话标识
     * @return 广播记录列表;无记录时返回空列表
     */
    List<LogoutBroadcast> selectBySid(@Param("realmId") Long realmId, @Param("sid") String sid);

    /**
     * 查询域内最近若干条未删除广播记录,按主键倒序。
     *
     * @param realmId 身份域主键
     * @param limit   返回条数上限
     * @return 广播记录列表;无记录时返回空列表
     */
    List<LogoutBroadcast> selectRecent(@Param("realmId") Long realmId, @Param("limit") int limit);
}
