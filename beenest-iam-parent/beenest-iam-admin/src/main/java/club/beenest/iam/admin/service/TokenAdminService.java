package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AdminTokenResponse;
import club.beenest.iam.common.result.PageResult;

/**
 * 已签发令牌(OAuth2 授权)管理服务。
 * <p>响应只含授权元信息(持有人、客户端、scope、过期时间),不返回令牌值。</p>
 */
public interface TokenAdminService {

    /**
     * 分页查询指定用户的已签发授权。
     * <p>登录名必填,查询按用户收敛范围;客户端过滤按注册客户端主键精确匹配。</p>
     *
     * @param username 用户登录名,必填
     * @param clientId 注册客户端主键过滤,可空
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     * @return 授权分页结果,不含令牌值
     * @throws BusinessException 登录名为空或用户不属于当前域
     */
    PageResult<AdminTokenResponse> page(String username, String clientId, int pageNum, int pageSize);

    /**
     * 吊销单条授权,访问令牌与刷新令牌一并失效。
     * <p>授权归属按注册客户端的域判定;跨域与不存在返回同一错误。</p>
     *
     * @param authorizationId 授权主键
     * @throws BusinessException 授权不存在、已过期或不属于当前域
     */
    void revoke(String authorizationId);

    /**
     * 吊销指定用户的全部授权,用于账号被盗时的批量失效。
     *
     * @param userId 用户主键
     * @return 被吊销的授权数量
     * @throws BusinessException 用户不存在或不属于当前域
     */
    int revokeByUser(Long userId);
}
