package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.UserRegisterDTO;
import org.apereo.cas.beenest.dto.UserUpdateDTO;
import org.apereo.cas.beenest.entity.CasAppAccess;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.beenest.service.UserAdminService;
import org.apereo.cas.beenest.vo.CasUserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器
 * <p>
 * 提供用户注册、更新、删除、启禁用等管理 API。
 * 使用强类型 DTO 接收请求，VO 返回脱敏后的用户信息。
 */
@Slf4j
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
public class CasUserAdminController {

    private final UserAdminService userAdminService;
    private final AppAccessService appAccessService;

    /**
     * 注册用户（可指定目标应用，自动赋权）
     */
    @PostMapping("/register")
    public R<CasUserVO> registerUser(@Valid @RequestBody UserRegisterDTO dto) {
        var user = userAdminService.registerUser(dto);
        return R.ok(CasUserVO.fromDO(user));
    }

    /**
     * 更新用户（只允许修改安全字段）
     */
    @PutMapping("/{userId}")
    public R<Void> updateUser(@PathVariable String userId, @Valid @RequestBody UserUpdateDTO dto) {
        userAdminService.updateUser(userId, dto);
        return R.ok();
    }

    /**
     * 删除用户（软删除）
     */
    @DeleteMapping("/{userId}")
    public R<Void> deleteUser(@PathVariable String userId) {
        userAdminService.deleteUser(userId);
        return R.ok();
    }

    /**
     * 用户详情（脱敏返回）
     */
    @GetMapping("/{userId}")
    public R<CasUserVO> getUser(@PathVariable String userId) {
        var user = userAdminService.getUser(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }
        return R.ok(CasUserVO.fromDO(user));
    }

    /**
     * 查询用户可访问应用
     */
    @GetMapping("/{userId}/apps")
    public R<List<CasAppAccess>> getUserApps(@PathVariable String userId) {
        return R.ok(appAccessService.getUserApps(userId));
    }

    /**
     * 启用/禁用用户
     */
    @PostMapping("/{userId}/status")
    public R<Void> updateStatus(@PathVariable String userId,
                                 @RequestBody Map<String, Integer> request) {
        Integer status = request.get("status");
        if (status == null) {
            return R.fail(400, "状态不能为空");
        }
        userAdminService.updateStatus(userId, status);
        return R.ok();
    }
}
