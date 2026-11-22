package club.beenest.iam.console.service;

import club.beenest.iam.console.dto.ConsoleSessionResponse;

import java.util.List;

/**
 * 会话自助服务。
 * <p>用户只能查看与注销自己的会话,注销他人会话属于管理端能力。</p>
 */
public interface ConsoleSessionService {

    /**
     * 查询本人全部会话记录。
     *
     * @param currentSid 当前请求所用会话标识,用于标记本机
     * @return 会话列表,按建立时间倒序
     */
    List<ConsoleSessionResponse> list(String currentSid);

    /**
     * 注销本人某个会话。
     *
     * @param sid        目标会话标识
     * @param currentSid 当前请求所用会话标识,不允许通过此接口注销
     */
    void revoke(String sid, String currentSid);
}
