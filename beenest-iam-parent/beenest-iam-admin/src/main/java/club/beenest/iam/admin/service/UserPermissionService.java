package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.EffectivePermissionResponse;
import club.beenest.iam.admin.dto.PermissionCandidateResponse;
import club.beenest.iam.admin.dto.PermissionOverrideRequest;
import club.beenest.iam.admin.dto.PermissionOverrideResponse;
import club.beenest.iam.admin.dto.PermissionScopeResponse;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 用户级权限治理。
 * <p>特批是对角色权限的个体化修正:GRANT 临时补权,REVOKE 临时收权;
 * 同一权限只能存在一条有效特批,重新设置覆盖原记录。</p>
 */
public interface UserPermissionService {

    /**
     * 查询用户全部特批,含已过期记录,过期记录仅用于追溯。
     *
     * @param userId 用户主键
     * @return 特批列表,含权限编码与生效状态
     * @throws BusinessException 用户不存在或不属于当前域
     */
    List<PermissionOverrideResponse> listOverrides(Long userId);

    /**
     * 查询可用于筛选权限的应用范围。
     *
     * @param userId 用户主键
     * @return 当前域应用列表
     * @throws BusinessException 用户不存在或不属于当前域
     */
    List<PermissionScopeResponse> listScopes(Long userId);

    /**
     * 分页查询权限候选,并标记用户当前特批状态。
     *
     * @param userId       用户主键
     * @param keyword      编码或名称模糊匹配,可空
     * @param appServiceId 应用命名空间过滤,可空表示全部命名空间
     * @param pageNum      页码,从 1 开始
     * @param pageSize     每页条数
     * @return 权限候选分页结果
     * @throws BusinessException 用户或应用不存在、跨域
     */
    PageResult<PermissionCandidateResponse> pageCandidates(Long userId, String keyword, Long appServiceId,
                                                           int pageNum, int pageSize);

    /**
     * 计算用户的有效权限明细,拆解角色直授、菜单派生、用户特批各来源。
     *
     * @param userId       用户主键
     * @param appServiceId 应用命名空间过滤,可空表示全部
     * @return 有效权限明细,含有效角色与来源链路
     * @throws BusinessException 用户或应用不存在、跨域
     */
    EffectivePermissionResponse inspectEffective(Long userId, Long appServiceId);

    /**
     * 设置或覆盖用户对单个权限的特批。
     * <p>不允许修改当前登录账号自身的特批;过期时间必须晚于当前时间;
     * 保存后权限代次抬升,特批在下一次认证或新签发令牌时生效。</p>
     *
     * @param userId       用户主键
     * @param permissionId 权限主键
     * @param request      特批参数,含 GRANT/REVOKE 类型、事由与过期时间
     * @return 保存后的特批
     * @throws BusinessException 用户或权限不存在、目标为本人或过期时间不合法
     */
    PermissionOverrideResponse saveOverride(Long userId, Long permissionId, PermissionOverrideRequest request);

    /**
     * 删除用户对单个权限的特批。
     * <p>不允许删除当前登录账号自身的特批;删除后权限代次抬升。</p>
     *
     * @param userId       用户主键
     * @param permissionId 权限主键
     * @throws BusinessException 用户不存在、目标为本人或特批不存在
     */
    void deleteOverride(Long userId, Long permissionId);
}
