package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.UserSessionModel;

import java.util.List;

/**
 * 用户会话存储 SPI。
 * <p>authz 模块经此接口读写会话记录;persistence 模块提供实现。</p>
 */
public interface UserSessionStore {

    /**
     * 落库一条会话记录。
     *
     * @param session 会话记录(不含主键)
     * @return 回填记录 id 后的会话
     */
    UserSessionModel insert(UserSessionModel session);

    /**
     * 按会话标识查询。
     *
     * @param sid 会话标识
     * @return 会话记录;不存在时返回 null
     */
    UserSessionModel findBySid(String sid);

    /**
     * 按会话标识更新状态。
     *
     * @param sid   会话标识
     * @param state 新状态:ACTIVE / LOGGED_OUT / EXPIRED
     */
    void updateState(String sid, String state);

    /**
     * 刷新最近活跃时间。
     * <p>会话存续期间按节流节奏调用,同时作为空闲超时的起算点。</p>
     *
     * @param sid 会话标识
     */
    void touch(String sid);

    /**
     * 查询用户的全部会话记录。
     *
     * @param realmId 所属身份域
     * @param userId  用户主键
     * @return 会话记录列表;无记录返回空列表
     */
    List<UserSessionModel> listByUser(Long realmId, Long userId);

    /**
     * 查询用户仍标记为活跃的会话记录,按主键倒序(由新到旧)。
     * <p>数据库状态可能滞后于 Redis 中的真实存续情况,调用方仍需自行确认会话是否存活。</p>
     *
     * @param realmId 所属身份域
     * @param userId  用户主键
     * @return 活跃会话记录列表;无记录返回空列表
     */
    List<UserSessionModel> listActiveByUser(Long realmId, Long userId);

    /**
     * 仅当记录仍为活跃时置为已过期。
     * <p>条件更新保证过期状态只被记录一次,避免巡检与守护过滤器同时判定时重复记录。</p>
     *
     * @param sid 会话标识
     * @return 实际修改了状态时为 true
     */
    boolean expireIfActive(String sid);

    /**
     * 列出仍标记为活跃的会话记录。
     * <p>会话运行期状态在 Redis,数据库行可能滞后为 ACTIVE;巡检任务据此收尾。</p>
     *
     * @param limit 单次取回上限
     * @return 活跃会话记录列表
     */
    List<UserSessionModel> listActive(int limit);

    /**
     * 更新会话记录的登录方式。
     * <p>管理端在线会话列表读取数据库行,补验升级后需在此同步登录方式。</p>
     *
     * @param sid       会话标识
     * @param loginType 新的登录方式;为 null 表示本次不改
     */
    void updateLoginType(String sid, String loginType);
}
