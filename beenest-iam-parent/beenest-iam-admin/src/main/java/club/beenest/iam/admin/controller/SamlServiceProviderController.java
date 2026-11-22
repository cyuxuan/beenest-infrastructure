package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.SamlServiceProviderRequest;
import club.beenest.iam.admin.dto.SamlServiceProviderResponse;
import club.beenest.iam.admin.service.SamlServiceProviderService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SAML 服务方登记管理接口。
 * <p>本平台作为身份提供方时,下游应用在此登记后才能发起单点登录。
 * 端点地址由服务端按当前域推导,管理员把它连同元数据地址交给对端即可。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/saml-sp")
public class SamlServiceProviderController {

    private final SamlServiceProviderService service;
    /** 对外基址:端点展示按配置推导,不取 Host 头 */
    private final IamIssuerBaseUrlResolver baseUrlResolver;

    public SamlServiceProviderController(SamlServiceProviderService service,
                                         IamIssuerBaseUrlResolver baseUrlResolver) {
        this.service = service;
        this.baseUrlResolver = baseUrlResolver;
    }

    /**
     * 列出当前域全部 SAML 服务方登记。
     *
     * @return 登记列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('saml-sp:view')")
    public Result<List<SamlServiceProviderResponse>> list() {
        return Result.success(service.list());
    }

    /**
     * 读取单个登记。
     *
     * @param id 登记主键
     * @return 登记详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('saml-sp:view')")
    public Result<SamlServiceProviderResponse> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    /**
     * 新增登记。
     *
     * @param request 登记请求
     * @return 新增后的登记
     */
    @PostMapping
    @PreAuthorize("hasAuthority('saml-sp:update')")
    @LogAudit(action = "SAML_SP_CREATE", description = "新增 SAML 服务方登记")
    public Result<SamlServiceProviderResponse> create(@Valid @RequestBody SamlServiceProviderRequest request) {
        return Result.success(service.create(request));
    }

    /**
     * 修改登记。
     *
     * @param id      登记主键
     * @param request 登记请求
     * @return 修改后的登记
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('saml-sp:update')")
    @LogAudit(action = "SAML_SP_UPDATE", description = "修改 SAML 服务方登记")
    public Result<SamlServiceProviderResponse> update(@PathVariable Long id,
                                                      @Valid @RequestBody SamlServiceProviderRequest request) {
        return Result.success(service.update(id, request));
    }

    /**
     * 删除登记。
     *
     * @param id 登记主键
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('saml-sp:update')")
    @LogAudit(action = "SAML_SP_DELETE", description = "删除 SAML 服务方登记")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    /**
     * 当前域的 IdP 端点地址,供管理员交给 SP 侧登记。
     *
     * @param request 当前请求,用于推导站点源地址
     * @return 实体 ID、单点登录、单点登出与元数据地址
     */
    @GetMapping("/endpoints")
    @PreAuthorize("hasAuthority('saml-sp:view')")
    public Result<Map<String, String>> endpoints(HttpServletRequest request) {
        return Result.success(service.endpoints(origin(request)));
    }

    /**
     * 当前请求的站点源地址。
     *
     * @param request 当前请求
     * @return 形如 {@code https://iam.example.com} 的源地址
     */
    private String origin(HttpServletRequest request) {
        return baseUrlResolver.resolve(request);
    }
}
