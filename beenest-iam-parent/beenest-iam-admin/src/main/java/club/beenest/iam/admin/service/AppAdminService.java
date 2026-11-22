package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AppAdminCreateRequest;
import club.beenest.iam.admin.dto.AppAdminResponse;
import club.beenest.iam.admin.dto.ManagedAppResponse;

import java.util.List;

/**
 * 应用管理员名单服务。
 * <p>管理员关系按当前身份域隔离,主体可为用户或用户组。</p>
 */
public interface AppAdminService {

    /**
     * 查询当前用户可管理的应用列表。
     * <p>持有全局应用管理权限时返回本域全部应用,其他用户仅返回直授或经用户组继承的管理范围。</p>
     *
     * @return 可管理应用列表
     */
    List<ManagedAppResponse> listManagedApps();

    /**
     * 查询应用的管理员名单。
     *
     * @param appId 应用服务主键
     * @return 管理员名单,含主体展示名与编码
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<AppAdminResponse> listAdmins(Long appId);

    /**
     * 新增应用管理员。
     * <p>同一主体重复登记时被拒绝。</p>
     *
     * @param appId   应用服务主键
     * @param request 管理员创建参数,主体类型限 USER 或 GROUP
     * @return 新增后的管理员
     * @throws BusinessException 应用不存在、主体不属于当前域或该主体已是应用管理员
     */
    AppAdminResponse addAdmin(Long appId, AppAdminCreateRequest request);

    /**
     * 移除应用管理员。
     *
     * @param appId   应用服务主键
     * @param adminId 管理员关系主键
     * @throws BusinessException 应用不存在,或管理员关系不属于该应用
     */
    void removeAdmin(Long appId, Long adminId);
}
