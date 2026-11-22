package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AdminSessionResponse;
import club.beenest.iam.admin.dto.LogoutBroadcastResponse;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 管理端会话管理服务。
 * <p>会话与广播记录均限定在当前身份域。</p>
 */
public interface SessionAdminService {

    /**
     * 分页查询当前域会话记录。
     *
     * @param username 登录名过滤,可空
     * @param state    会话状态过滤,可空
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     * @return 会话分页结果
     */
    PageResult<AdminSessionResponse> page(String username, String state, int pageNum, int pageSize);

    /**
     * 强制注销指定会话,与用户主动退出走同一条登出路径:
     * 广播单点登出、删除 Redis 快照、会话记录置为已注销。
     *
     * @param sid 会话标识
     * @throws BusinessException 会话不存在或不属于当前域
     */
    void revoke(String sid);

    /**
     * 查询指定会话的登出广播投递记录。
     *
     * @param sid 会话标识
     * @return 广播投递记录列表,用于定位未收到通知的应用
     */
    List<LogoutBroadcastResponse> broadcasts(String sid);

    /**
     * 查询当前域最近的登出广播记录。
     *
     * @param limit 返回条数上限
     * @return 广播投递记录列表,用于排查近期投递失败的应用
     */
    List<LogoutBroadcastResponse> recentBroadcasts(int limit);
}
