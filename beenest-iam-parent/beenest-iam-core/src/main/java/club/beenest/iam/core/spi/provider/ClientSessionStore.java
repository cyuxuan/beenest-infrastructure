package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.ClientSessionModel;

import java.util.List;

/**
 * 客户端会话存储 SPI。
 * <p>authz 模块在授权码签发时写入、在登出时读取;persistence 模块提供实现。</p>
 */
public interface ClientSessionStore {

    /**
     * 记录某会话在某个客户端上已登录。
     * <p>幂等:同一(会话, 客户端)重复调用只保留一条,同一会话多次授权不产生重复行。</p>
     *
     * @param realmId       所属域
     * @param userSessionId 用户会话记录主键
     * @param userId        用户主键
     * @param clientId      客户端主键
     * @return 落库后的记录
     */
    ClientSessionModel record(Long realmId, Long userSessionId, Long userId, Long clientId);

    /**
     * 按会话标识列出该会话登录过的全部客户端。
     *
     * @param realmId 所属域(防止跨域读到别的域的记录)
     * @param sid     会话标识
     * @return 客户端会话列表;无记录返回空列表
     */
    List<ClientSessionModel> listBySid(Long realmId, String sid);

    /**
     * 按用户与客户端列出该用户在指定应用上的全部会话记录。
     * <p>授权被收回时据此定位需要定向登出的会话;同一用户可能有多台设备各一条。</p>
     *
     * @param realmId  所属域
     * @param userId   用户主键
     * @param clientId 客户端主键
     * @return 客户端会话列表;无记录返回空列表
     */
    List<ClientSessionModel> listByUserAndClient(Long realmId, Long userId, Long clientId);

    /**
     * 关闭某个用户在一个客户端上的全部会话记录。
     *
     * @param realmId  所属域
     * @param userId   用户主键
     * @param clientId 客户端主键
     * @return 被关闭的记录数
     */
    int closeByUserAndClient(Long realmId, Long userId, Long clientId);

    /**
     * 关闭某会话在单个客户端上的记录(定向登出后调用)。
     *
     * @param sid      会话标识
     * @param clientId 客户端主键
     * @return 被关闭的记录数
     */
    int closeBySidAndClient(String sid, Long clientId);

    /**
     * 关闭会话下的全部客户端会话(登出时调用)。
     * <p>只做软删除,保留投递/审计所需的痕迹。</p>
     *
     * @param sid 会话标识
     * @return 被关闭的记录数
     */
    int closeBySid(String sid);
}
