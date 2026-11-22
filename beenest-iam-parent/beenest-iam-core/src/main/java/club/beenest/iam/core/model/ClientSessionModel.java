package club.beenest.iam.core.model;

/**
 * 客户端会话(用户会话在单个接入应用上的投影)。
 * <p>一次授权码换取令牌建立一条记录,同一 IAM 会话可在多个应用上各有一条。
 * 单点登出时按 sid 取出这批记录,逐个向应用的后通道登出地址投递通知。</p>
 *
 * @param id            主键
 * @param realmId       所属身份域
 * @param userSessionId 用户会话记录主键(iam_user_session.id)
 * @param sid           IAM 会话标识(与后通道登出令牌中的 sid 一致)
 * @param userId        用户主键
 * @param clientId      客户端主键(iam_client.id)
 * @param clientKey     客户端标识(client_id)
 */
public record ClientSessionModel(
        Long id,
        Long realmId,
        Long userSessionId,
        String sid,
        Long userId,
        Long clientId,
        String clientKey
) {
}
