package club.beenest.iam.console.controller;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.console.dto.ConsoleSessionResponse;
import club.beenest.iam.console.service.ConsoleSessionService;
import club.beenest.iam.authz.session.IamSessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话管理接口。
 * <p>供用户在用户中心查看登录设备并踢出可疑会话。</p>
 */
@RestController
@RequestMapping("/api/v1/console")
public class SessionController {

    private final ConsoleSessionService sessionService;

    public SessionController(ConsoleSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 查询本人会话列表。
     *
     * @param request 当前请求,用于识别本机会话
     * @return 会话列表,按建立时间倒序
     */
    @GetMapping("/sessions")
    public Result<List<ConsoleSessionResponse>> sessions(HttpServletRequest request) {
        return Result.success(sessionService.list(currentSid(request)));
    }

    /**
     * 注销本人指定会话。
     *
     * @param sid     目标会话标识
     * @param request 当前请求,用于识别本机会话
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 会话不存在、不属于本人或目标为当前会话
     */
    @DeleteMapping("/sessions/{sid}")
    @LogAudit(action = "CONSOLE_SESSION_REVOKE", description = "注销本人会话")
    public Result<Void> revoke(@PathVariable String sid, HttpServletRequest request) {
        sessionService.revoke(sid, currentSid(request));
        return Result.success();
    }

    /**
     * 查询本人权限码集合(前端据此渲染菜单与按钮)。
     *
     * @return 权限码列表
     */
    @GetMapping("/my-permissions")
    public Result<List<String>> myPermissions() {
        return Result.success(CurrentUserHolder.require().getAuthorities());
    }

    /**
     * 读取当前请求所用的会话标识。
     *
     * @param request 当前请求
     * @return 会话标识;纯令牌访问等无会话场景返回 null
     */
    private static String currentSid(HttpServletRequest request) {
        return IamSessionCookie.read(request);
    }
}
