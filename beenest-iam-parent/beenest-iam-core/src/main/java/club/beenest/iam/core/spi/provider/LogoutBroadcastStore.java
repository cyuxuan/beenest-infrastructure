package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.LogoutBroadcastModel;

import java.util.List;

/**
 * 单点登出广播记录存储 SPI。
 */
public interface LogoutBroadcastStore {

    /**
     * 落库一条待投递记录。
     *
     * @param model 待落库记录(状态 PENDING)
     * @return 回填主键后的记录
     */
    LogoutBroadcastModel insert(LogoutBroadcastModel model);

    /**
     * 回写投递结果。
     *
     * @param id           记录主键
     * @param state        投递状态:SENT / FAILED / SKIPPED
     * @param httpStatus   应用返回的 HTTP 状态码;未发出时为 null
     * @param errorMessage 失败原因;成功时为 null
     */
    void updateResult(Long id, String state, Integer httpStatus, String errorMessage);

    /**
     * 按会话标识列出广播记录。
     *
     * @param realmId 所属身份域
     * @param sid     会话标识
     * @return 广播记录列表;无记录返回空列表
     */
    List<LogoutBroadcastModel> listBySid(Long realmId, String sid);

    /**
     * 列出域内最近若干条广播记录。
     *
     * @param realmId 所属身份域
     * @param limit   返回条数上限
     * @return 按时间倒序的广播记录
     */
    List<LogoutBroadcastModel> listRecent(Long realmId, int limit);
}
