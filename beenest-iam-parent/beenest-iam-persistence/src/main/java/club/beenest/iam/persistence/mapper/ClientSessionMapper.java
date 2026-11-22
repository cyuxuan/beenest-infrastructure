package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.ClientSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 客户端会话 Mapper(iam_client_session 单表)。
 * <p>记录用户在哪些客户端建立过会话,按会话与客户端唯一;删除为逻辑删除。</p>
 */
public interface ClientSessionMapper {

    /**
     * 按会话与客户端查询未删除记录,用于幂等写入。
     *
     * @param userSessionId 用户会话主键
     * @param clientId      客户端主键
     * @return 客户端会话;不存在返回 null
     */
    ClientSession selectBySessionAndClient(@Param("userSessionId") Long userSessionId,
                                           @Param("clientId") Long clientId);

    /**
     * 按域与会话标识联查该会话登录过的客户端,结果含客户端标识,用于登出广播寻址。
     *
     * @param realmId 身份域主键
     * @param sid     会话标识
     * @return 客户端会话列表;无记录时返回空列表
     */
    List<ClientSession> selectBySid(@Param("realmId") Long realmId, @Param("sid") String sid);

    /**
     * 按域、用户与客户端联查该用户在此应用上的全部会话,结果含 sid,用于授权收回后的定向登出。
     *
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param clientId 客户端主键
     * @return 客户端会话列表;无记录时返回空列表
     */
    List<ClientSession> selectByUserAndClient(@Param("realmId") Long realmId,
                                              @Param("userId") Long userId,
                                              @Param("clientId") Long clientId);

    /**
     * 插入客户端会话,deleted 固定写入 FALSE。
     *
     * @param clientSession 客户端会话实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(ClientSession clientSession);

    /**
     * 按域、用户与客户端逻辑删除会话记录。
     *
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param clientId 客户端主键
     * @return 受影响行数
     */
    int softDeleteByUserAndClient(@Param("realmId") Long realmId,
                                  @Param("userId") Long userId,
                                  @Param("clientId") Long clientId);

    /**
     * 按会话标识与客户端逻辑删除单条会话记录。
     *
     * @param sid      会话标识
     * @param clientId 客户端主键
     * @return 受影响行数
     */
    int softDeleteBySidAndClient(@Param("sid") String sid, @Param("clientId") Long clientId);

    /**
     * 按会话标识逻辑删除该会话下的全部客户端会话。
     *
     * @param sid 会话标识
     * @return 受影响行数
     */
    int softDeleteBySid(@Param("sid") String sid);
}
