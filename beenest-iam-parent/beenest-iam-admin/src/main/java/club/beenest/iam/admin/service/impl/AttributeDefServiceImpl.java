package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AttributeDefCreateRequest;
import club.beenest.iam.admin.dto.AttributeDefResponse;
import club.beenest.iam.admin.dto.AttributeDefUpdateRequest;
import club.beenest.iam.admin.service.AttributeDefService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.AttributeDef;
import club.beenest.iam.persistence.manager.IamAttributeDefManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户属性定义管理实现。
 * <p>属性定义限定在当前身份域;创建时校验属性键域内唯一、类型白名单与 ENUM 可选值。</p>
 */
@Service
public class AttributeDefServiceImpl implements AttributeDefService {

    /** 合法属性类型 */
    private static final List<String> VALID_TYPES = List.of("STRING", "NUMBER", "BOOLEAN", "DATE", "ENUM", "JSON");

    private final IamAttributeDefManager attributeDefManager;

    public AttributeDefServiceImpl(IamAttributeDefManager attributeDefManager) {
        this.attributeDefManager = attributeDefManager;
    }

    @Override
    public List<AttributeDefResponse> list() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return attributeDefManager.listByRealm(realmId).stream().map(this::toResponse).toList();
    }

    @Override
    public AttributeDefResponse get(Long id) {
        return toResponse(requireInRealm(id));
    }

    @Override
    public AttributeDefResponse create(AttributeDefCreateRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 1. 属性键域内唯一
        if (attributeDefManager.getByKey(realmId, request.attrKey()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "属性键已存在: " + request.attrKey());
        }
        // 2. 类型白名单
        String attrType = request.attrType() == null || request.attrType().isBlank()
                ? "STRING" : request.attrType().toUpperCase();
        if (!VALID_TYPES.contains(attrType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的属性类型: " + attrType);
        }
        // 3. ENUM 必须给可选值
        if ("ENUM".equals(attrType) && (request.enumOptions() == null || request.enumOptions().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "ENUM 类型必须提供可选值");
        }
        AttributeDef def = new AttributeDef();
        def.setRealmId(realmId);
        def.setAttrKey(request.attrKey());
        def.setAttrName(request.attrName());
        def.setAttrType(attrType);
        def.setRequired(Boolean.TRUE.equals(request.required()));
        def.setSearchable(Boolean.TRUE.equals(request.searchable()));
        def.setUniqueFlag(Boolean.TRUE.equals(request.uniqueFlag()));
        def.setEnumOptions(request.enumOptions());
        def.setValidationRegex(request.validationRegex());
        def.setDescription(request.description());
        attributeDefManager.create(def);
        return toResponse(attributeDefManager.getById(def.getId()));
    }

    @Override
    public AttributeDefResponse update(Long id, AttributeDefUpdateRequest request) {
        AttributeDef def = requireInRealm(id);
        if (request.attrType() != null) {
            String attrType = request.attrType().toUpperCase();
            if (!VALID_TYPES.contains(attrType)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的属性类型: " + attrType);
            }
            def.setAttrType(attrType);
        }
        if (request.attrName() != null) {
            def.setAttrName(request.attrName());
        }
        if (request.required() != null) {
            def.setRequired(request.required());
        }
        if (request.searchable() != null) {
            def.setSearchable(request.searchable());
        }
        if (request.uniqueFlag() != null) {
            def.setUniqueFlag(request.uniqueFlag());
        }
        if (request.enumOptions() != null) {
            def.setEnumOptions(request.enumOptions());
        }
        if (request.validationRegex() != null) {
            def.setValidationRegex(request.validationRegex());
        }
        if (request.description() != null) {
            def.setDescription(request.description());
        }
        attributeDefManager.update(def);
        return toResponse(attributeDefManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        requireInRealm(id);
        attributeDefManager.remove(id);
    }

    /**
     * 读取属于当前域的属性定义。
     *
     * @param id 属性定义主键
     * @return 属性定义
     * @throws BusinessException 属性定义不存在或不属于当前域
     */
    private AttributeDef requireInRealm(Long id) {
        AttributeDef def = attributeDefManager.getById(id);
        if (def == null || !CurrentUserHolder.require().getRealmId().equals(def.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "属性定义不存在: " + id);
        }
        return def;
    }

    /**
     * 属性定义转响应。
     *
     * @param def 属性定义
     * @return 属性定义响应
     */
    private AttributeDefResponse toResponse(AttributeDef def) {
        return new AttributeDefResponse(def.getId(), def.getAttrKey(), def.getAttrName(), def.getAttrType(),
                def.getRequired(), def.getSearchable(), def.getUniqueFlag(), def.getEnumOptions(),
                def.getValidationRegex(), def.getDescription(), def.getCreatedTime());
    }
}
