package club.beenest.iam.console.controller;

import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.console.dto.ChangePasswordRequest;
import club.beenest.iam.console.dto.ConsoleProfileResponse;
import club.beenest.iam.console.dto.ProfileUpdateRequest;
import club.beenest.iam.console.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人档案接口。
 * <p>自助端端点只需已认证,可访问的资源由"是否属于本人"决定,不使用管理端权限码。</p>
 */
@RestController
@RequestMapping("/api/v1/console")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 查询本人档案。
     *
     * @return 本人档案
     */
    @GetMapping("/profile")
    public Result<ConsoleProfileResponse> profile() {
        return Result.success(profileService.get());
    }

    /**
     * 修改本人档案。
     *
     * @param request 档案修改请求,仅包含展示类信息与扩展属性
     * @return 修改后的档案
     */
    @PutMapping("/profile")
    @LogAudit(action = "CONSOLE_PROFILE_UPDATE", description = "修改个人档案")
    public Result<ConsoleProfileResponse> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        return Result.success(profileService.update(request));
    }

    /**
     * 修改本人登录口令。
     *
     * @param request 改密请求,需携带原口令
     * @return 无业务数据的成功响应
     */
    @PutMapping("/password")
    @LogAudit(action = "CONSOLE_PASSWORD_CHANGE", description = "修改本人口令")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        profileService.changePassword(request);
        return Result.success();
    }
}
