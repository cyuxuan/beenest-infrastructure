package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.RealmCreateRequest;
import club.beenest.iam.admin.dto.RealmKeyResponse;
import club.beenest.iam.admin.dto.RealmResponse;
import club.beenest.iam.admin.dto.RealmUpdateRequest;
import club.beenest.iam.admin.service.RealmService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
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
 * 身份域管理接口。
 * <p>身份域是全局资源(不隶属于其它身份域),本组接口不带域前缀;
 * 权限粒度由每个端点上的 {@code @PreAuthorize} 校验。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/realms")
public class RealmController {

    private final RealmService realmService;

    public RealmController(RealmService realmService) {
        this.realmService = realmService;
    }

    /**
     * 分页查询身份域。
     *
     * @param realmKey  域标识(精确匹配,可空)
     * @param realmName 域名称(模糊匹配,可空)
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 身份域分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('realm:view')")
    public Result<PageResult<RealmResponse>> page(
            @RequestParam(required = false) String realmKey,
            @RequestParam(required = false) String realmName,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(realmService.page(realmKey, realmName, pageNum, pageSize));
    }

    /**
     * 查询单个身份域。
     *
     * @param id 身份域主键
     * @return 身份域详情
     * @throws club.beenest.iam.common.exception.BusinessException 身份域不存在
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('realm:view')")
    public Result<RealmResponse> get(@PathVariable Long id) {
        return Result.success(realmService.get(id));
    }

    /**
     * 新建身份域。
     *
     * @param request 新建请求,包含域标识、名称与主题;域标识全局唯一,创建时同步初始化登录与口令策略行
     * @return 新建的身份域
     * @throws club.beenest.iam.common.exception.BusinessException 域标识已存在或主题目录不存在
     */
    @PostMapping
    @PreAuthorize("hasAuthority('realm:create')")
    @LogAudit(action = "REALM_CREATE", description = "新建身份域")
    public Result<RealmResponse> create(@Valid @RequestBody RealmCreateRequest request) {
        return Result.success(realmService.create(request));
    }

    /**
     * 更新身份域,仅覆盖请求中显式给出的字段;域标识不可变更。
     *
     * @param id      身份域主键
     * @param request 更新请求
     * @return 更新后的身份域
     * @throws club.beenest.iam.common.exception.BusinessException 身份域不存在或主题目录不存在
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realm:update')")
    @LogAudit(action = "REALM_UPDATE", description = "更新身份域")
    public Result<RealmResponse> update(@PathVariable Long id,
                                        @Valid @RequestBody RealmUpdateRequest request) {
        return Result.success(realmService.update(id, request));
    }

    /**
     * 删除身份域(逻辑删除,域下用户、角色等数据保持可追溯;默认域不允许删除)。
     *
     * @param id 身份域主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 身份域不存在或为默认域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realm:delete')")
    @LogAudit(action = "REALM_DELETE", description = "删除身份域")
    public Result<Void> delete(@PathVariable Long id) {
        realmService.delete(id);
        return Result.success();
    }

    /**
     * 查询域的签名密钥列表(不含密钥材料)。
     * <p>轮换后旧密钥仍在列表中,JWKS 继续发布其公钥供已签发令牌验签过渡。</p>
     *
     * @param id 身份域主键
     * @return 密钥生命周期信息列表
     * @throws club.beenest.iam.common.exception.BusinessException 身份域不存在
     */
    @GetMapping("/{id}/keys")
    @PreAuthorize("hasAuthority('realm-key:view')")
    public Result<List<RealmKeyResponse>> listKeys(@PathVariable Long id) {
        return Result.success(realmService.listKeys(id));
    }

    /**
     * 轮换域签名密钥:退役旧密钥并生成新密钥。
     * <p>旧密钥退役但保留:JWKS 继续发布其公钥,已签发令牌在自然过期前仍可验签;
     * 属高危运维动作,应低频执行并留下审计记录。</p>
     *
     * @param id 身份域主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 身份域不存在
     */
    @PostMapping("/{id}/keys/rotate")
    @PreAuthorize("hasAuthority('realm-key:rotate')")
    @LogAudit(action = "REALM_KEY_ROTATE", description = "轮换域签名密钥")
    public Result<Void> rotateKey(@PathVariable Long id) {
        realmService.rotateKey(id);
        return Result.success();
    }

    /**
     * 查询可用的协议页主题目录名(身份域表单的主题下拉)。
     * <p>主题是 classpath:/themes/ 下的静态资源目录,启动时扫描;新增主题需放置目录并重启生效。</p>
     *
     * @return 主题目录名列表
     */
    @GetMapping("/theme-options")
    @PreAuthorize("hasAuthority('realm:view')")
    public Result<List<String>> themeOptions() {
        return Result.success(realmService.listThemes());
    }
}
