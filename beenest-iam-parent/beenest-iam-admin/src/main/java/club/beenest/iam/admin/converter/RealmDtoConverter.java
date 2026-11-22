package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.RealmCreateRequest;
import club.beenest.iam.admin.dto.RealmResponse;
import club.beenest.iam.admin.dto.RealmUpdateRequest;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.persistence.entity.Realm;
import org.springframework.stereotype.Component;

/**
 * 身份域管理端对象转换器。
 * <p>负责请求/实体/响应三者之间的字段搬运,不含业务规则。</p>
 */
@Component
public class RealmDtoConverter {

    /**
     * 身份域实体转响应。
     *
     * @param realm 身份域实体,可为 null
     * @return 身份域响应,入参为 null 时返回 null
     */
    public RealmResponse toResponse(Realm realm) {
        if (realm == null) {
            return null;
        }
        return new RealmResponse(
                realm.getId(),
                realm.getRealmKey(),
                realm.getRealmName(),
                realm.getEnabled(),
                realm.getIsDefault(),
                realm.getAccessTokenTtl(),
                realm.getRefreshTokenTtl(),
                realm.getSsoSessionIdle(),
                realm.getSsoSessionMax(),
                SubjectMode.parse(realm.getSubjectMode()).code(),
                realm.getTheme(),
                realm.getCreatedTime(),
                realm.getUpdatedTime());
    }

    /**
     * 新建请求转实体,只写落库字段,时间与删除标记交给数据库默认值。
     * <p>新建的域一律不是默认域,默认域由初始化数据指定且不可通过接口变更;
     * 主体策略缺省时显式写入,保证两条落库路径结果一致。</p>
     *
     * @param request 新建请求
     * @return 身份域实体
     */
    public Realm toEntity(RealmCreateRequest request) {
        Realm realm = new Realm();
        realm.setRealmKey(request.realmKey());
        realm.setRealmName(request.realmName());
        realm.setEnabled(request.enabled() == null || request.enabled());
        // 默认域由初始化数据指定,接口新建的域一律非默认
        realm.setIsDefault(false);
        realm.setAccessTokenTtl(request.accessTokenTtl());
        realm.setRefreshTokenTtl(request.refreshTokenTtl());
        realm.setSsoSessionIdle(request.ssoSessionIdle());
        realm.setSsoSessionMax(request.ssoSessionMax());
        // 域级策略永远有值(DDL 非空 + 默认 LOGIN_NAME),缺省时显式写入,不依赖数据库默认值,
        // 保持建域与建域后修改两条路径的落库结果一致
        realm.setSubjectMode(SubjectMode.parse(request.subjectMode()).code());
        // 主题空串等同未配置(跟随全局);存在性由服务层校验,转换层只搬运
        realm.setTheme(request.theme() == null || request.theme().isBlank()
                ? null : request.theme().trim());
        return realm;
    }

    /**
     * 将更新请求中的非空字段写入实体,域标识不可修改。
     * <p>主题字段:空串表示显式改回跟随全局(写 NULL),非空表示指定主题,null 表示不更新。</p>
     *
     * @param realm   库中身份域实体
     * @param request 更新请求
     */
    public void applyUpdate(Realm realm, RealmUpdateRequest request) {
        if (request.realmName() != null) {
            realm.setRealmName(request.realmName());
        }
        if (request.enabled() != null) {
            realm.setEnabled(request.enabled());
        }
        if (request.accessTokenTtl() != null) {
            realm.setAccessTokenTtl(request.accessTokenTtl());
        }
        if (request.refreshTokenTtl() != null) {
            realm.setRefreshTokenTtl(request.refreshTokenTtl());
        }
        if (request.ssoSessionIdle() != null) {
            realm.setSsoSessionIdle(request.ssoSessionIdle());
        }
        if (request.ssoSessionMax() != null) {
            realm.setSsoSessionMax(request.ssoSessionMax());
        }
        if (request.subjectMode() != null) {
            realm.setSubjectMode(SubjectMode.parse(request.subjectMode()).code());
        }
        // 主题:空串显式改回跟随全局(写 NULL),非空指定主题,null 不更新该项
        if (request.theme() != null) {
            realm.setTheme(request.theme().isBlank() ? null : request.theme().trim());
        }
    }
}
