package club.beenest.iam.admin.scim;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * SCIM 2.0 用户端点。
 * <p>供 HR / 目录系统按标准协议同步人员,鉴权走平台现有的 Bearer 令牌,
 * 要求令牌携带 {@code scim:manage} 权限码;数据范围是令牌所属身份域。
 * 该权限码建议只授予专用服务账号的角色,不与人工管理员的角色混用,
 * 这样同步链路的审计记录能一眼区分"系统同步"与"人工操作"。</p>
 */
@RestController
@RequestMapping(value = "/scim/v2/Users", produces = ScimConstants.MEDIA_TYPE)
@PreAuthorize("hasAuthority('scim:manage')")
public class ScimUserController {

    private final ScimUserService scimUserService;

    public ScimUserController(ScimUserService scimUserService) {
        this.scimUserService = scimUserService;
    }

    /**
     * 查询用户列表。
     *
     * @param filter     过滤表达式,如 {@code userName eq "alice"}
     * @param startIndex 起始序号,从 1 开始
     * @param count      本页条数,上限 100
     * @return SCIM 列表响应
     */
    @GetMapping
    public ScimListResponse<ScimUserResource> list(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count) {
        return scimUserService.list(ScimFilter.parse(filter), startIndex, count, baseUrl());
    }

    /**
     * 查询单个用户。
     *
     * @param id 用户主键
     * @return 用户资源
     */
    @GetMapping("/{id}")
    public ScimUserResource get(@PathVariable("id") String id) {
        return scimUserService.get(id, baseUrl());
    }

    /**
     * 新建用户。
     *
     * @param resource 用户资源
     * @return 新建后的用户资源
     */
    @PostMapping(consumes = ScimConstants.MEDIA_TYPE)
    @ResponseStatus(HttpStatus.CREATED)
    public ScimUserResource create(@RequestBody ScimUserResource resource) {
        return scimUserService.create(resource, baseUrl());
    }

    /**
     * 整体替换用户属性。
     *
     * @param id       用户主键
     * @param resource 用户资源
     * @return 更新后的用户资源
     */
    @PutMapping(value = "/{id}", consumes = ScimConstants.MEDIA_TYPE)
    public ScimUserResource replace(@PathVariable("id") String id,
                                    @RequestBody ScimUserResource resource) {
        return scimUserService.replace(id, resource, baseUrl());
    }

    /**
     * 部分更新用户属性。
     *
     * @param id      用户主键
     * @param request 部分更新请求
     * @return 更新后的用户资源
     */
    @PatchMapping(value = "/{id}", consumes = ScimConstants.MEDIA_TYPE)
    public ScimUserResource patch(@PathVariable("id") String id,
                                  @RequestBody ScimPatchRequest request) {
        return scimUserService.patch(id, request, baseUrl());
    }

    /**
     * 删除用户。
     *
     * @param id 用户主键
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        scimUserService.delete(id);
    }

    /**
     * 资源地址前缀,用于拼 {@code meta.location}。
     *
     * @return 形如 {@code https://host/scim/v2/Users}
     */
    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/scim/v2/Users")
                .toUriString();
    }
}
