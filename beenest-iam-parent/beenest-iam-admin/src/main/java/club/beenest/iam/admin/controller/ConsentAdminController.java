package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.ConsentRecordResponse;
import club.beenest.iam.admin.service.ConsentAdminService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 授权同意管理接口。
 * <p>用于查询用户的 scope 同意记录并回收某应用已授予的同意;仅返回客户端与用户
 * 都属于当前域的记录,撤销后用户下次访问该客户端会重新看到授权确认页。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/consents")
public class ConsentAdminController {

    private final ConsentAdminService consentAdminService;

    public ConsentAdminController(ConsentAdminService consentAdminService) {
        this.consentAdminService = consentAdminService;
    }

    /**
     * 列举同意记录。
     *
     * @param principalName 登录名模糊过滤(可选)
     * @param clientId      客户端标识精确过滤,接受 client_key 或内部主键(可选)
     * @return 扁平的同意记录列表,仅含当前域内客户端与用户的记录
     */
    @GetMapping
    @PreAuthorize("hasAuthority('consent:view')")
    public Result<List<ConsentRecordResponse>> list(
            @RequestParam(required = false) String principalName,
            @RequestParam(required = false) String clientId) {
        return Result.success(consentAdminService.list(principalName, clientId));
    }

    /**
     * 撤销指定用户对指定客户端的同意。
     *
     * @param clientId      client_key 或内部主键
     * @param principalName 用户登录名
     * @throws club.beenest.iam.common.exception.BusinessException 用户或客户端不存在、跨域,或同意记录不存在
     */
    @DeleteMapping("/{clientId}/{principalName}")
    @PreAuthorize("hasAuthority('consent:revoke')")
    @LogAudit(action = "CONSENT_REVOKE", description = "撤销授权同意")
    public Result<Void> revoke(@PathVariable String clientId,
                               @PathVariable String principalName) {
        consentAdminService.revoke(clientId, principalName);
        return Result.success();
    }
}
