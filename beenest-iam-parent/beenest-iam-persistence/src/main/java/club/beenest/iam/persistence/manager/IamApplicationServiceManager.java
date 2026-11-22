package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.AppServiceGrant;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.mapper.AppServiceGrantMapper;
import club.beenest.iam.persistence.mapper.ApplicationServiceMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用服务存储(iam_application_service / iam_app_service_grant)。
 * <p>应用服务是应用门户的数据来源,门户按可见性与授权关系筛出用户可启动的应用。</p>
 */
@Component
public class IamApplicationServiceManager {

    private final ApplicationServiceMapper applicationServiceMapper;
    private final AppServiceGrantMapper appServiceGrantMapper;

    public IamApplicationServiceManager(ApplicationServiceMapper applicationServiceMapper,
                                        AppServiceGrantMapper appServiceGrantMapper) {
        this.applicationServiceMapper = applicationServiceMapper;
        this.appServiceGrantMapper = appServiceGrantMapper;
    }

    /**
     * 按主键查询。
     *
     * @param id 应用服务主键
     * @return 应用服务;不存在返回 null
     */
    public ApplicationService getById(Long id) {
        return applicationServiceMapper.selectById(id);
    }

    /**
     * 按域与应用编码查询(唯一性校验)。
     *
     * @param realmId 身份域主键
     * @param appCode 应用编码
     * @return 应用服务;不存在返回 null
     */
    public ApplicationService getByAppCode(Long realmId, String appCode) {
        return applicationServiceMapper.selectByRealmIdAndAppCode(realmId, appCode);
    }

    /**
     * 按绑定的客户端查询(令牌按应用裁剪与唯一绑定校验)。
     *
     * @param realmId 身份域主键
     * @param ssoClientPk 绑定的客户端主键;为空返回 null
     * @return 应用服务;不存在返回 null
     */
    public ApplicationService getBySsoClientPk(Long realmId, Long ssoClientPk) {
        return ssoClientPk == null ? null
                : applicationServiceMapper.selectBySsoClientPk(realmId, ssoClientPk);
    }

    /**
     * 查询域内全部应用服务(启动台按可见性筛选使用)。
     *
     * @param realmId 身份域主键
     * @return 应用服务列表
     */
    public List<ApplicationService> listByRealmId(Long realmId) {
        return applicationServiceMapper.selectByRealmId(realmId);
    }

    /**
     * 按主键集合批量查询应用服务。
     *
     * @param ids 应用服务主键集合
     * @return 命中的应用服务列表;入参为空返回空列表
     */
    public List<ApplicationService> listByIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of() : applicationServiceMapper.selectByIds(ids);
    }

    /**
     * 分页查询应用服务。
     *
     * @param realmId 身份域主键
     * @param condition 查询条件,非空字段参与过滤
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 应用服务分页结果
     */
    public PageResult<ApplicationService> page(Long realmId, ApplicationService condition,
                                               int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ApplicationService> list = applicationServiceMapper.selectByCondition(realmId, condition);
        PageInfo<ApplicationService> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 新建应用服务。
     *
     * @param applicationService 应用服务实体
     * @return 受影响行数
     */
    public int create(ApplicationService applicationService) {
        return applicationServiceMapper.insert(applicationService);
    }

    /**
     * 更新应用服务非空字段。
     *
     * @param applicationService 应用服务实体,需含主键
     * @return 受影响行数
     */
    public int update(ApplicationService applicationService) {
        return applicationServiceMapper.updateById(applicationService);
    }

    /**
     * 逻辑删除应用服务。
     *
     * @param id 应用服务主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return applicationServiceMapper.softDeleteById(id);
    }

    /**
     * 查询应用的全部授权记录(含 USER / ROLE 等各类主体)。
     *
     * @param appServiceId 应用服务主键
     * @return 授权记录列表
     */
    public List<AppServiceGrant> listGrants(Long appServiceId) {
        return appServiceGrantMapper.selectByAppServiceId(appServiceId);
    }

    /**
     * 整体覆盖应用在某一主体类型下的授权名单。
     * <p>先逻辑删除该类型的原有记录,再批量写入新名单。</p>
     *
     * @param realmId 身份域主键
     * @param appServiceId 应用服务主键
     * @param subjectType 主体类型(USER / ROLE / GROUP)
     * @param subjectIds 目标主体主键,为空表示清空
     */
    @org.springframework.transaction.annotation.Transactional
    public void replaceGrants(Long realmId, Long appServiceId, String subjectType, List<Long> subjectIds) {
        appServiceGrantMapper.softDeleteByAppServiceId(appServiceId, subjectType);
        if (subjectIds != null && !subjectIds.isEmpty()) {
            appServiceGrantMapper.insertBatch(realmId, appServiceId, subjectType, subjectIds);
        }
    }

    /**
     * 查询某类主体被授权的应用服务主键集合。
     *
     * @param subjectType 主体类型:USER / ROLE / GROUP
     * @param subjectIds 主体主键集合
     * @return 应用服务主键集合;主体为空时返回空集合
     */
    public Set<Long> listGrantedAppServiceIds(String subjectType, List<Long> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> appIds = new HashSet<>();
        for (var grant : appServiceGrantMapper.selectBySubject(subjectType, subjectIds)) {
            appIds.add(grant.getAppServiceId());
        }
        return appIds;
    }

    /**
     * 逻辑删除某个主体的全部应用授权。
     * <p>角色、用户组或用户被删除时调用,授权行不保留指向已删主体的记录。</p>
     *
     * @param subjectType 主体类型:USER / ROLE / GROUP
     * @param subjectId 主体主键
     * @return 受影响行数
     */
    public int removeGrantsBySubject(String subjectType, Long subjectId) {
        return appServiceGrantMapper.softDeleteBySubject(subjectType, subjectId);
    }
}
