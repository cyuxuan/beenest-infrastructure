package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AdminTokenResponse;
import club.beenest.iam.admin.service.TokenAdminService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已签发令牌管理接口。
 * <p>响应只含授权元信息,不返回令牌值;授权归属按注册客户端的域判定,
 * 查询与吊销均限定在当前身份域。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/tokens")
public class TokenAdminController {

    private final TokenAdminService tokenAdminService;

    public TokenAdminController(TokenAdminService tokenAdminService) {
        this.tokenAdminService = tokenAdminService;
    }

    /**
     * 分页查询用户已签发授权。
     *
     * @param username  登录名(必填),查询按用户收敛范围
     * @param clientId  注册客户端主键过滤(可选)
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 授权分页结果,不含令牌值
     * @throws club.beenest.iam.common.exception.BusinessException 登录名为空或用户不属于当前域
     */
    @GetMapping
    @PreAuthorize("hasAuthority('token:view')")
    public Result<PageResult<AdminTokenResponse>> page(
            @RequestParam String username,
            @RequestParam(required = false) String clientId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(tokenAdminService.page(username, clientId, pageNum, pageSize));
    }

    /**
     * 吊销单条授权(访问令牌与刷新令牌一并失效)。
     *
     * @param authorizationId 授权主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 授权不存在、已过期或不属于当前域
     */
    @DeleteMapping("/{authorizationId}")
    @PreAuthorize("hasAuthority('token:revoke')")
    @LogAudit(action = "TOKEN_REVOKE", description = "吊销指定授权")
    public Result<Void> revoke(@PathVariable String authorizationId) {
        tokenAdminService.revoke(authorizationId);
        return Result.success();
    }

    /**
     * 吊销用户全部授权。
     *
     * @param userId 用户主键
     * @return 被吊销的授权数量
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('token:revoke')")
    @LogAudit(action = "TOKEN_REVOKE_ALL", description = "吊销用户全部授权")
    public Result<Integer> revokeByUser(@PathVariable Long userId) {
        return Result.success(tokenAdminService.revokeByUser(userId));
    }
}
