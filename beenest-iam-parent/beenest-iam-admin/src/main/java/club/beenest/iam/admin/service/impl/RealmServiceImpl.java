package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RealmDtoConverter;
import club.beenest.iam.admin.dto.RealmCreateRequest;
import club.beenest.iam.admin.dto.RealmKeyResponse;
import club.beenest.iam.admin.dto.RealmResponse;
import club.beenest.iam.admin.dto.RealmUpdateRequest;
import club.beenest.iam.admin.service.RealmService;
import club.beenest.iam.authz.jwks.RealmKeyService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.theme.ThemeRegistry;
import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.persistence.entity.Realm;
import club.beenest.iam.persistence.manager.IamRealmManager;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 身份域管理实现。
 * <p>域标识创建后不可变更;创建域时同步初始化登录与口令策略行,主题需在主题目录中存在。
 * 密钥列表只暴露生命周期信息,轮换时退役旧密钥并生成新密钥。</p>
 * <p>身份域本身是租户边界,不能由租户自己管理:非平台域的管理员只能查看与修改自身域,
 * 域的创建、删除与签名密钥轮换只允许平台域管理员执行。否则任一租户管理员拿到
 * {@code realm:update} 就能改到别的域,包括默认域。</p>
 */
@Service
public class RealmServiceImpl implements RealmService {

    private final IamRealmManager realmManager;
    private final RealmDtoConverter dtoConverter;
    private final RealmKeyService realmKeyService;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final ThemeRegistry themeRegistry;
    /** 平台域标识:该域的管理员视作平台管理员 */
    private final String platformRealmKey;

    public RealmServiceImpl(IamRealmManager realmManager, RealmDtoConverter dtoConverter,
                            RealmKeyService realmKeyService,
                            IamSecurityPolicyManager securityPolicyManager,
                            ThemeRegistry themeRegistry,
                            @Value("${iam.platform-realm-key:default}") String platformRealmKey) {
        this.realmManager = realmManager;
        this.dtoConverter = dtoConverter;
        this.realmKeyService = realmKeyService;
        this.securityPolicyManager = securityPolicyManager;
        this.themeRegistry = themeRegistry;
        this.platformRealmKey = platformRealmKey;
    }

    @Override
    public PageResult<RealmResponse> page(String realmKey, String realmName, int pageNum, int pageSize) {
        // 1. 组装单表查询条件(域标识精确匹配,域名称模糊匹配)
        Realm condition = new Realm();
        condition.setRealmKey(realmKey);
        condition.setRealmName(realmName);
        // 1.1 非平台管理员只能看到自己所在的域,入参中的域标识被覆盖
        IamUserPrincipal current = CurrentUserHolder.optional();
        if (current == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!isPlatformAdmin(current)) {
            condition.setRealmKey(current.getRealmKey());
        }
        // 2. 分页查询后逐条转换为响应对象
        PageResult<Realm> page = realmManager.page(condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(dtoConverter::toResponse).toList());
    }

    @Override
    public RealmResponse get(Long id) {
        return dtoConverter.toResponse(requireVisibleRealm(id));
    }

    @Override
    @Transactional
    public RealmResponse create(RealmCreateRequest request) {
        // 0. 建域是平台级动作
        requirePlatformAdmin("创建身份域");
        // 1. 域标识全局唯一(逻辑删除的域不占用标识)
        if (realmManager.getByRealmKey(request.realmKey()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "域标识已存在: " + request.realmKey());
        }
        // 2. 主题必须在 themes/ 下真实存在
        validateTheme(request.theme());
        // 3. 落库并回读完整字段(时间列由数据库填充)
        Realm realm = dtoConverter.toEntity(request);
        realmManager.create(realm);
        // 4. 同步初始化登录与口令策略行
        securityPolicyManager.ensureLoginPolicy(realm.getId());
        securityPolicyManager.ensurePasswordPolicy(realm.getId());
        return dtoConverter.toResponse(realmManager.getById(realm.getId()));
    }

    @Override
    public RealmResponse update(Long id, RealmUpdateRequest request) {
        // 1. 目标域必须存在且对当前管理员可见
        Realm realm = requireVisibleRealm(id);
        // 2. 主题存在性校验(空串 = 显式改回跟随全局,合法)
        validateTheme(request.theme());
        // 3. 仅更新请求中显式给出的字段
        dtoConverter.applyUpdate(realm, request);
        realmManager.update(realm);
        return dtoConverter.toResponse(realmManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        // 0. 删域是平台级动作:域下所有用户、角色与令牌会一并失去入口
        requirePlatformAdmin("删除身份域");
        // 1. 目标域必须存在
        Realm realm = requireRealm(id);
        // 2. 默认域是登录页与协议端点的兜底入口,删除后系统不可用
        if (Boolean.TRUE.equals(realm.getIsDefault())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "默认域不允许删除");
        }
        // 3. 逻辑删除,域下的用户/角色等数据保持可追溯
        realmManager.remove(id);
    }

    /**
     * 读取存在且对当前管理员可见的身份域。
     *
     * @param id 身份域主键
     * @return 身份域
     * @throws BusinessException 身份域不存在,或不属于当前管理员所在的域
     */
    private Realm requireVisibleRealm(Long id) {
        Realm realm = requireRealm(id);
        IamUserPrincipal current = CurrentUserHolder.optional();
        if (current == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!isPlatformAdmin(current) && !realm.getId().equals(current.getRealmId())) {
            // 跨域访问一律按"不存在"处理,不透露其它域是否存在
            throw new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + id);
        }
        return realm;
    }

    /**
     * 判断当前管理员是否属于平台域。
     *
     * @param current 当前登录主体
     * @return true 表示是平台域管理员
     */
    private boolean isPlatformAdmin(IamUserPrincipal current) {
        return platformRealmKey.equals(current.getRealmKey());
    }

    /**
     * 要求当前主体是平台域管理员。
     *
     * @param action 动作描述,用于错误文案
     * @throws BusinessException 非平台域管理员
     */
    private void requirePlatformAdmin(String action) {
        IamUserPrincipal current = CurrentUserHolder.optional();
        if (current == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!isPlatformAdmin(current)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅平台管理员可" + action);
        }
    }

    /**
     * 读取存在的身份域。
     *
     * @param id 身份域主键
     * @return 身份域
     * @throws BusinessException 身份域不存在
     */
    private Realm requireRealm(Long id) {
        Realm realm = realmManager.getById(id);
        if (realm == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + id);
        }
        return realm;
    }

    /**
     * 校验域主题目录真实存在,空值表示跟随全局。
     *
     * @param theme 主题标识
     * @throws BusinessException 主题目录不存在
     */
    private void validateTheme(String theme) {
        if (theme != null && !theme.isBlank() && !themeRegistry.exists(theme.trim())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "主题目录不存在: " + theme.trim() + "(可用主题: " + String.join(", ", themeRegistry.list()) + ")");
        }
    }

    @Override
    public List<RealmKeyResponse> listKeys(Long id) {
        // 1. 域必须存在且对当前管理员可见
        requireVisibleRealm(id);
        // 2. 只暴露生命周期信息,不暴露任何密钥材料
        return realmKeyService.listKeys(id).stream()
                .map(this::toKeyResponse)
                .toList();
    }

    @Override
    public void rotateKey(Long id) {
        // 0. 轮换会让该域已签发的令牌在有效期内全部验签失败,属平台级动作
        requirePlatformAdmin("轮换域签名密钥");
        // 1. 域必须存在
        requireRealm(id);
        // 2. 轮换:退役旧密钥(保留供验签)+ 生成新密钥
        realmKeyService.rotateKey(id);
    }

    /**
     * 密钥模型转响应,不包含密钥材料。
     *
     * @param key 密钥模型
     * @return 密钥响应
     */
    private RealmKeyResponse toKeyResponse(RealmKeyModel key) {
        return new RealmKeyResponse(
                key.id(),
                key.keyType(),
                key.active(),
                key.activatedTime(),
                key.retiredTime());
    }

    @Override
    public List<String> listThemes() {
        return themeRegistry.list();
    }
}
