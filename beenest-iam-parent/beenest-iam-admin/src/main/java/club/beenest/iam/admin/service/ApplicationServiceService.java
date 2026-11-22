package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.admin.dto.AppServiceGrantResponse;
import club.beenest.iam.admin.dto.ApplicationServiceResponse;
import club.beenest.iam.admin.dto.ApplicationServiceUpdateRequest;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 应用服务管理服务。
 * <p>操作范围限定在调用者所属身份域内。应用服务与客户端的一致性
 * (接入方式为 OIDC 时必须绑定本域客户端)在服务层校验。</p>
 */
public interface ApplicationServiceService {

    /**
     * 分页查询当前域应用服务。
     *
     * @param appCode    应用编码模糊匹配,可空
     * @param appName    应用名称模糊匹配,可空
     * @param appType    应用类型精确匹配,可空
     * @param category   分类精确匹配,可空
     * @param visibility 可见性精确匹配,可空
     * @param authorizationMode 授权模式精确匹配,可空
     * @param enabled    启用状态精确匹配,可空
     * @param pageNum    页码,从 1 开始
     * @param pageSize   每页条数
     * @return 应用服务分页结果
     */
    PageResult<ApplicationServiceResponse> page(String appCode, String appName, String appType,
                                                String category, String visibility, String authorizationMode,
                                                Boolean enabled, int pageNum, int pageSize);

    /**
     * 查询单个应用服务。
     *
     * @param id 应用服务主键
     * @return 应用服务详情
     * @throws BusinessException 应用不存在或不属于当前域
     */
    ApplicationServiceResponse get(Long id);

    /**
     * 新建应用服务。
     * <p>应用编码在域内唯一;校验失败由事务回滚。</p>
     *
     * @param request 应用服务创建参数
     * @return 新建后的应用服务
     * @throws BusinessException 应用编码重复或接入配置不合法
     */
    ApplicationServiceResponse create(ApplicationServiceCreateRequest request);

    /**
     * 更新应用服务。
     * <p>仅更新请求中显式给出的字段,更新后仍需满足接入一致性约束。</p>
     *
     * @param id      应用服务主键
     * @param request 应用服务更新参数
     * @return 更新后的应用服务
     * @throws BusinessException 应用不存在或更新后的配置不合法
     */
    ApplicationServiceResponse update(Long id, ApplicationServiceUpdateRequest request);

    /**
     * 逻辑删除应用服务,其管理员与角色映射随应用一并清理。
     *
     * @param id 应用服务主键
     * @throws BusinessException 应用不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 查询应用在某一主体类型下的授权名单。
     *
     * @param id          应用服务主键
     * @param subjectType 主体类型:USER / ROLE / GROUP
     * @return 该主体类型下的授权名单
     * @throws BusinessException 应用不存在、不属于当前域或主体类型非法
     */
    List<AppServiceGrantResponse> listGrants(Long id, String subjectType);

    /**
     * 整体覆盖应用在某一主体类型下的授权名单,可见性为 ROLE 时决定门户卡片的可见范围。
     * <p>授权主体必须存在且属于当前域。</p>
     *
     * @param id          应用服务主键
     * @param subjectType 主体类型:USER / ROLE / GROUP
     * @param subjectIds  目标主体主键集合;空集合表示清空
     * @return 覆盖后的授权名单
     * @throws BusinessException 应用不存在、主体类型非法或授权主体不属于当前域
     */
    List<AppServiceGrantResponse> replaceGrants(Long id, String subjectType, List<Long> subjectIds);
}
