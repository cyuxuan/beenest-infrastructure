package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.MenuUpdateRequest;
import club.beenest.iam.admin.service.MenuService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单管理接口。
 * <p>菜单按应用归属,查询必须带应用主键;角色授予菜单即派生菜单上挂载的权限,
 * 授予动作在角色侧维护。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    /**
     * 查询应用在菜单托管范围内的菜单(扁平列表,含停用项)。
     *
     * @param appServiceId 应用服务主键
     * @return 该应用的菜单列表
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或不属于当前域
     */
    @GetMapping
    @PreAuthorize("hasAuthority('menu:view')")
    public Result<List<MenuResponse>> list(@RequestParam @NotNull Long appServiceId) {
        return Result.success(menuService.listByApp(appServiceId));
    }

    /**
     * 查询单个菜单。
     *
     * @param id 菜单主键
     * @return 菜单详情
     * @throws club.beenest.iam.common.exception.BusinessException 菜单不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:view')")
    public Result<MenuResponse> get(@PathVariable Long id) {
        return Result.success(menuService.get(id));
    }

    /**
     * 新建菜单或按钮。
     *
     * @param request 新建请求,包含所属应用、父菜单、编码与挂载权限;编码在应用内唯一,父菜单与权限必须属于该应用
     * @return 新建的菜单
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在、父菜单或权限不属于该应用或菜单编码重复
     */
    @PostMapping
    @PreAuthorize("hasAuthority('menu:create')")
    @LogAudit(action = "MENU_CREATE", description = "新建菜单")
    public Result<MenuResponse> create(@Valid @RequestBody MenuCreateRequest request) {
        return Result.success(menuService.create(request));
    }

    /**
     * 更新菜单,仅覆盖请求中显式给出的字段;父级与编码不可修改。
     *
     * @param id      菜单主键
     * @param request 更新请求
     * @return 更新后的菜单
     * @throws club.beenest.iam.common.exception.BusinessException 菜单不存在或挂载的权限不属于该应用
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:update')")
    @LogAudit(action = "MENU_UPDATE", description = "修改菜单")
    public Result<MenuResponse> update(@PathVariable Long id, @Valid @RequestBody MenuUpdateRequest request) {
        return Result.success(menuService.update(id, request));
    }

    /**
     * 删除菜单及其子项(逻辑删除),并清理相关角色-菜单关联。
     *
     * @param id 菜单主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 菜单不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('menu:delete')")
    @LogAudit(action = "MENU_DELETE", description = "删除菜单")
    public Result<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.success();
    }
}
