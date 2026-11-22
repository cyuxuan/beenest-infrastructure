package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户会话记录 Mapper(iam_user_session 单表)。
 * <p>会话状态以 ACTIVE、EXPIRED 等字符串表示;查询排除逻辑删除行。</p>
 */
public interface UserSessionMapper {

    /**
     * 插入会话,deleted 固定写入 FALSE。
     *
     * @param userSession 会话实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserSession userSession);

    /**
     * 按主键更新状态、最近活跃时间与过期时间。
     *
     * @param userSession 会话实体,id 必填
     * @return 受影响行数
     */
    int updateById(UserSession userSession);

    /**
     * 按会话标识查询未删除会话。
     *
     * @param sid 会话标识
     * @return 会话;不存在或已删除返回 null
     */
    UserSession selectBySid(@Param("sid") String sid);

    /**
     * 按会话标识更新状态(注销或过期)。
     *
     * @param sid   会话标识
     * @param state 目标状态
     * @return 受影响行数
     */
    int updateStateBySid(@Param("sid") String sid, @Param("state") String state);

    /**
     * 仅当会话仍为 ACTIVE 时置为 EXPIRED。
     *
     * @param sid 会话标识
     * @return 受影响行数;已非 ACTIVE 时为 0
     */
    int expireIfActive(@Param("sid") String sid);

    /**
     * 刷新最近活跃时间,会话存续期间按节流节奏调用。
     *
     * @param sid            会话标识
     * @param lastActiveTime 最近活跃时间
     * @return 受影响行数
     */
    int touchBySid(@Param("sid") String sid, @Param("lastActiveTime") java.time.LocalDateTime lastActiveTime);

    /**
     * 查询用户的未删除会话记录,按主键倒序。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 会话列表;无记录时返回空列表
     */
    List<UserSession> selectByUserId(@Param("realmId") Long realmId, @Param("userId") Long userId);

    /**
     * 查询用户仍为 ACTIVE 的未删除会话,按主键倒序(即由新到旧)。
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 活跃会话列表;无记录时返回空列表
     */
    List<UserSession> selectActiveByUserId(@Param("realmId") Long realmId, @Param("userId") Long userId);

    /**
     * 管理端分页查询,按主键倒序。
     * <p>按登录名模糊与状态精确过滤;登录名经 iam_user 关联过滤,会话表不冗余登录名。</p>
     *
     * @param realmId  身份域主键
     * @param username 登录名,模糊匹配
     * @param state    会话状态
     * @return 会话列表;无匹配时返回空列表
     */
    List<UserSession> selectByCondition(@Param("realmId") Long realmId,
                                        @Param("username") String username,
                                        @Param("state") String state);

    /**
     * 统计域内 ACTIVE 状态且未删除的会话数。
     *
     * @param realmId 身份域主键
     * @return 会话数
     */
    long countActiveByRealm(@Param("realmId") Long realmId);

    /**
     * 跨域列出仍为 ACTIVE 的未删除会话,按主键升序,用于会话巡检。
     *
     * @param limit 返回条数上限
     * @return 会话列表;无记录时返回空列表
     */
    List<UserSession> selectActive(@Param("limit") int limit);

    /**
     * 按会话标识更新登录方式,用于补验升级后的同步。
     *
     * @param sid       会话标识
     * @param loginType 登录方式
     * @return 受影响行数
     */
    int updateLoginTypeBySid(@Param("sid") String sid, @Param("loginType") String loginType);
}
