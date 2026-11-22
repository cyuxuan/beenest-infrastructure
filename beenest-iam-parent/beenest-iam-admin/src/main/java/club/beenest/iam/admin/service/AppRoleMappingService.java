package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AppRoleMappingCreateRequest;
import club.beenest.iam.admin.dto.AppRoleMappingResponse;

import java.util.List;

/**
 * 应用角色映射服务,维护 MAPPED 授权模式应用的角色来源配置。
 * <p>映射在应用授权模式为 MAPPED 时参与令牌计算,其他模式下允许预配置。</p>
 */
public interface AppRoleMappingService {

    /**
     * 查询应用的全部角色映射,含停用项。
     *
     * @param appId 应用服务主键
     * @return 映射列表,含来源展示名与编码
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<AppRoleMappingResponse> list(Long appId);

    /**
     * 新增角色映射。
     * <p>来源类型限 GROUP 或 REALM_ROLE,REALM_ROLE 只接受域角色;同一来源到同一目标角色只保留一条。</p>
     *
     * @param appId   应用服务主键
     * @param request 映射创建参数
     * @return 新增后的映射
     * @throws BusinessException 应用不存在、来源非法或该映射已存在
     */
    AppRoleMappingResponse create(Long appId, AppRoleMappingCreateRequest request);

    /**
     * 删除角色映射。
     *
     * @param appId     应用服务主键
     * @param mappingId 映射主键
     * @throws BusinessException 应用不存在,或映射不属于该应用
     */
    void delete(Long appId, Long mappingId);
}
