package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.LoginEventResponse;
import club.beenest.iam.admin.service.LoginEventService;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 登录事件查询接口(只读)。
 * <p>与管理操作审计({@code /audit-logs})互补:审计记录管理员的操作动作,
 * 本接口记录登录成败、MFA 与登出事件,查询按当前身份域隔离。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/login-events")
public class LoginEventController {

    private final LoginEventService loginEventService;

    public LoginEventController(LoginEventService loginEventService) {
        this.loginEventService = loginEventService;
    }

    /**
     * 分页查询登录事件,按时间倒序。
     *
     * @param username  登录名模糊过滤(可选)
     * @param eventType 事件类型: LOGIN_SUCCESS/LOGIN_FAILURE/MFA_SUCCESS/MFA_FAILURE/LOGOUT(可选)
     * @param ip        来源 IP 精确过滤(可选)
     * @param fromTime  起始时间(可选,ISO 格式)
     * @param toTime    结束时间(可选,ISO 格式)
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 当前域登录事件分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('login-event:view')")
    public Result<PageResult<LoginEventResponse>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String ip,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(loginEventService.page(
                username, eventType, ip, fromTime, toTime, pageNum, pageSize));
    }

    /**
     * 溯源某一来源地址的全部登录事件,按时间倒序。
     * <p>用于排查同一 IP 对多个账号的失败尝试。</p>
     *
     * @param ip       来源 IP,精确匹配
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 该 IP 的登录事件分页结果
     * @throws club.beenest.iam.common.exception.BusinessException 来源 IP 为空
     */
    @GetMapping("/ip/{ip}")
    @PreAuthorize("hasAuthority('login-event:view')")
    public Result<PageResult<LoginEventResponse>> ipHistory(
            @PathVariable String ip,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(loginEventService.ipHistory(ip, pageNum, pageSize));
    }
}
