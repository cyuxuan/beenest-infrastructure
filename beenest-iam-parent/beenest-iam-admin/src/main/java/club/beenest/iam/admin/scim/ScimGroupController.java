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
 * SCIM 2.0 组端点。
 * <p>供 HR / 目录系统同步组织与成员,鉴权与数据范围口径同用户端点。</p>
 */
@RestController
@RequestMapping(value = "/scim/v2/Groups", produces = ScimConstants.MEDIA_TYPE)
@PreAuthorize("hasAuthority('scim:manage')")
public class ScimGroupController {

    private final ScimGroupService scimGroupService;

    public ScimGroupController(ScimGroupService scimGroupService) {
        this.scimGroupService = scimGroupService;
    }

    /**
     * 查询组列表。
     *
     * @param filter     过滤表达式,如 {@code displayName eq "研发部"}
     * @param startIndex 起始序号,从 1 开始
     * @param count      本页条数,上限 100
     * @return SCIM 列表响应
     */
    @GetMapping
    public ScimListResponse<ScimGroupResource> list(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count) {
        return scimGroupService.list(ScimFilter.parse(filter), startIndex, count, baseUrl());
    }

    /**
     * 查询单个组,含成员列表。
     *
     * @param id 组主键
     * @return 组资源
     */
    @GetMapping("/{id}")
    public ScimGroupResource get(@PathVariable("id") String id) {
        return scimGroupService.get(id, baseUrl());
    }

    /**
     * 新建组。
     *
     * @param resource 组资源
     * @return 新建后的组资源
     */
    @PostMapping(consumes = ScimConstants.MEDIA_TYPE)
    @ResponseStatus(HttpStatus.CREATED)
    public ScimGroupResource create(@RequestBody ScimGroupResource resource) {
        return scimGroupService.create(resource, baseUrl());
    }

    /**
     * 整体替换组属性。
     *
     * @param id       组主键
     * @param resource 组资源
     * @return 更新后的组资源
     */
    @PutMapping(value = "/{id}", consumes = ScimConstants.MEDIA_TYPE)
    public ScimGroupResource replace(@PathVariable("id") String id,
                                     @RequestBody ScimGroupResource resource) {
        return scimGroupService.replace(id, resource, baseUrl());
    }

    /**
     * 部分更新组属性。
     *
     * @param id      组主键
     * @param request 部分更新请求
     * @return 更新后的组资源
     */
    @PatchMapping(value = "/{id}", consumes = ScimConstants.MEDIA_TYPE)
    public ScimGroupResource patch(@PathVariable("id") String id,
                                   @RequestBody ScimPatchRequest request) {
        return scimGroupService.patch(id, request, baseUrl());
    }

    /**
     * 删除组。
     *
     * @param id 组主键
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        scimGroupService.delete(id);
    }

    /**
     * 资源地址前缀,用于拼 {@code meta.location}。
     *
     * @return 形如 {@code https://host/scim/v2/Groups}
     */
    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/scim/v2/Groups")
                .toUriString();
    }
}
