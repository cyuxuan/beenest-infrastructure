package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AdminSessionResponse;
import club.beenest.iam.admin.dto.LogoutBroadcastResponse;
import club.beenest.iam.admin.service.SessionAdminService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端会话管理接口。
 * <p>会话与广播记录均限定在当前身份域;强制注销与用户主动退出走同一条登出路径。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
public class SessionAdminController {

    private final SessionAdminService sessionAdminService;

    public SessionAdminController(SessionAdminService sessionAdminService) {
        this.sessionAdminService = sessionAdminService;
    }

    /**
     * 分页查询会话记录。
     *
     * @param username 登录名过滤(可空)
     * @param state    会话状态过滤(可空)
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 当前域会话分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('session:view')")
    public Result<PageResult<AdminSessionResponse>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(sessionAdminService.page(username, state, pageNum, pageSize));
    }

    /**
     * 强制注销指定会话:广播单点登出、删除 Redis 快照、会话记录置为已注销。
     *
     * @param sid 会话标识
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 会话不存在或不属于当前域
     */
    @DeleteMapping("/{sid}")
    @PreAuthorize("hasAuthority('session:revoke')")
    @LogAudit(action = "SESSION_REVOKE", description = "强制注销会话")
    public Result<Void> revoke(@PathVariable String sid) {
        sessionAdminService.revoke(sid);
        return Result.success();
    }

    /**
     * 查询指定会话的登出广播投递记录。
     *
     * @param sid 会话标识
     * @return 广播投递记录列表,用于定位未收到通知的应用
     */
    @GetMapping("/{sid}/broadcasts")
    @PreAuthorize("hasAuthority('session:broadcast')")
    public Result<List<LogoutBroadcastResponse>> broadcasts(@PathVariable String sid) {
        return Result.success(sessionAdminService.broadcasts(sid));
    }

    /**
     * 域内最近的登出广播记录(总览)。
     *
     * @param limit 返回条数上限
     * @return 广播投递记录列表,用于排查近期投递失败的应用
     */
    @GetMapping("/broadcasts")
    @PreAuthorize("hasAuthority('session:broadcast')")
    public Result<List<LogoutBroadcastResponse>> recentBroadcasts(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(sessionAdminService.recentBroadcasts(limit));
    }
}
