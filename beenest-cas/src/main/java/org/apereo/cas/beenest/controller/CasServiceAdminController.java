package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.CasServiceAuthMethodDTO;
import org.apereo.cas.beenest.dto.CasServiceDetailDTO;
import org.apereo.cas.beenest.dto.AppAccessGrantDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterResultDTO;
import org.apereo.cas.beenest.dto.CasServiceSummaryDTO;
import org.apereo.cas.beenest.dto.UserIdRequestDTO;
import org.apereo.cas.beenest.entity.CasAppAccess;
import org.apereo.cas.beenest.service.CasServiceAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CAS 服务/应用管理控制器
 * <p>
 * 通过 Apereo CAS 的 ServicesManager 管理注册应用，
 * 支持为每个应用配置认证策略和访问控制。
 */
@Slf4j
@RestController
@RequestMapping("/admin/service")
@RequiredArgsConstructor
public class CasServiceAdminController {

    private final CasServiceAdminService serviceAdminService;

    /**
     * 注册应用
     * <p>
     * 支持配置：
     * - 允许的认证方式（allowedAuthenticationHandlers）
     * - 返回的用户属性（allowedAttributes）
     * - 应用级访问控制开关（accessControlEnabled）
     */
    @PostMapping
    public R<CasServiceRegisterResultDTO> registerService(@Valid @RequestBody CasServiceRegisterDTO dto) {
        return R.ok(serviceAdminService.createServiceWithSecret(dto));
    }

    /**
     * 更新应用
     */
    @PutMapping("/{id}")
    public R<Void> updateService(@PathVariable Long id, @Valid @RequestBody CasServiceRegisterDTO dto) {
        serviceAdminService.updateService(id, dto);
        return R.ok();
    }

    /**
     * 删除应用
     */
    @DeleteMapping("/{id}")
    public R<Void> deleteService(@PathVariable Long id) {
        serviceAdminService.deleteService(id);
        return R.ok();
    }

    /**
     * 查询所有应用
     */
    @GetMapping("/list")
    public R<List<CasServiceSummaryDTO>> listServices() {
        return R.ok(serviceAdminService.listServices());
    }

    /**
     * 查询应用详情
     */
    @GetMapping("/{id}")
    public R<CasServiceDetailDTO> getService(@PathVariable Long id) {
        return R.ok(serviceAdminService.getService(id));
    }

    /**
     * 授予用户应用访问权（支持批量、过期时间、授权原因）
     */
    @PostMapping("/{serviceId}/grant")
    public R<Void> grantAccess(@PathVariable Long serviceId,
                                @Valid @RequestBody AppAccessGrantDTO dto) {
        if (dto.getUserIds() != null && !dto.getUserIds().isEmpty()) {
            serviceAdminService.batchGrantAccess(serviceId, dto.getUserIds(), dto.getAccessLevel(), dto.getGrantedBy(), dto.getReason());
        } else if (dto.getUserId() != null) {
            serviceAdminService.grantAccess(serviceId, dto.getUserId(),
                    dto.getAccessLevel(), dto.getGrantedBy(), dto.getReason(), dto.getExpireTime());
        } else {
            return R.fail(400, "userId 或 userIds 不能同时为空");
        }
        return R.ok();
    }

    /**
     * 撤销用户访问权
     */
    @PostMapping("/{serviceId}/revoke")
    public R<Void> revokeAccess(@PathVariable Long serviceId,
                                 @RequestBody UserIdRequestDTO request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            return R.fail(400, "userId 不能为空");
        }
        serviceAdminService.revokeAccess(serviceId, userId);
        return R.ok();
    }

    /**
     * 查询应用用户
     */
    @GetMapping("/{serviceId}/users")
    public R<List<CasAppAccess>> getAppUsers(@PathVariable Long serviceId) {
        return R.ok(serviceAdminService.getAppUsers(serviceId));
    }

    /**
     * 获取所有可用的认证方式列表
     */
    @GetMapping("/auth-methods")
    public R<List<CasServiceAuthMethodDTO>> getAuthMethods() {
        return R.ok(serviceAdminService.getAuthMethods());
    }
}
