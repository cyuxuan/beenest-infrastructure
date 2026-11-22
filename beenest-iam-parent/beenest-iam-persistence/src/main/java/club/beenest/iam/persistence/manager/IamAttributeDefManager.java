package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.AttributeDef;
import club.beenest.iam.persistence.entity.UserAttributeIndex;
import club.beenest.iam.persistence.mapper.AttributeDefMapper;
import club.beenest.iam.persistence.mapper.UserAttributeIndexMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户属性定义存储(iam_attribute_def / iam_user_attribute_index)。
 * <p>属性定义是用户扩展属性的声明式 Schema;searchable 属性在同事务内维护索引投影,
 * is_unique 决定是否参与唯一约束。</p>
 */
@Component
public class IamAttributeDefManager {

    private final AttributeDefMapper attributeDefMapper;
    private final UserAttributeIndexMapper attributeIndexMapper;

    public IamAttributeDefManager(AttributeDefMapper attributeDefMapper,
                                  UserAttributeIndexMapper attributeIndexMapper) {
        this.attributeDefMapper = attributeDefMapper;
        this.attributeIndexMapper = attributeIndexMapper;
    }

    // ===== 属性定义 CRUD =====

    /**
     * 按主键查询。
     *
     * @param id 属性定义主键
     * @return 属性定义;不存在返回 null
     */
    public AttributeDef getById(Long id) {
        return attributeDefMapper.selectById(id);
    }

    /**
     * 按域与属性键查询。
     *
     * @param realmId 身份域主键
     * @param attrKey 属性键
     * @return 属性定义;不存在返回 null
     */
    public AttributeDef getByKey(Long realmId, String attrKey) {
        return attributeDefMapper.selectByRealmIdAndKey(realmId, attrKey);
    }

    /**
     * 查询域内全部属性定义。
     *
     * @param realmId 身份域主键
     * @return 属性定义列表
     */
    public List<AttributeDef> listByRealm(Long realmId) {
        return attributeDefMapper.selectByRealmId(realmId);
    }

    /**
     * 新建属性定义。
     *
     * @param attributeDef 属性定义实体
     * @return 受影响行数
     */
    public int create(AttributeDef attributeDef) {
        return attributeDefMapper.insert(attributeDef);
    }

    /**
     * 更新属性定义非空字段。
     *
     * @param attributeDef 属性定义实体,需含主键
     * @return 受影响行数
     */
    public int update(AttributeDef attributeDef) {
        return attributeDefMapper.updateById(attributeDef);
    }

    /**
     * 逻辑删除属性定义。
     *
     * @param id 属性定义主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return attributeDefMapper.softDeleteById(id);
    }

    // ===== 属性索引投影维护 =====

    /**
     * 按属性定义重投影某用户的索引行。
     * <p>与 iam_user.attributes 在同事务内调用;仅 searchable 属性写入投影,
     * is_unique 标记决定是否参与唯一约束。</p>
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @param attributes 用户全部扩展属性(JSON 解析后的键值)
     * @param defs 域内全部属性定义
     */
    @Transactional
    public void reprojectIndexes(Long realmId, Long userId, Map<String, Object> attributes,
                                 List<AttributeDef> defs) {
        // 1. 先清理该用户既有投影行,再按定义重建(简单且可自愈)
        attributeIndexMapper.deleteByUserId(userId);
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        for (AttributeDef def : defs) {
            if (!Boolean.TRUE.equals(def.getSearchable())) {
                continue;
            }
            Object value = attributes.get(def.getAttrKey());
            if (value == null) {
                continue;
            }
            UserAttributeIndex index = new UserAttributeIndex();
            index.setRealmId(realmId);
            index.setUserId(userId);
            index.setAttrKey(def.getAttrKey());
            index.setAttrValue(String.valueOf(value));
            index.setIsUnique(Boolean.TRUE.equals(def.getUniqueFlag()));
            attributeIndexMapper.insert(index);
        }
    }

    /**
     * 校验用户属性是否违反唯一约束。
     *
     * @param realmId 身份域主键
     * @param excludeUserId 排除的用户主键(通常是本人)
     * @param attributes 待校验的属性键值
     * @param defs 域内全部属性定义
     * @return 冲突属性的键;无冲突返回 null
     */
    public String findUniqueConflict(Long realmId, Long excludeUserId,
                                     Map<String, Object> attributes, List<AttributeDef> defs) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        for (AttributeDef def : defs) {
            if (!Boolean.TRUE.equals(def.getUniqueFlag())) {
                continue;
            }
            Object value = attributes.get(def.getAttrKey());
            if (value == null) {
                continue;
            }
            UserAttributeIndex existing =
                    attributeIndexMapper.selectByRealmKeyValue(realmId, def.getAttrKey(), String.valueOf(value));
            if (existing != null && !existing.getUserId().equals(excludeUserId)) {
                return def.getAttrKey();
            }
        }
        return null;
    }

    /**
     * 按属性维度检索用户主键(管理端用户目录扩展)。
     *
     * @param realmId 身份域主键
     * @param attrKey 属性键
     * @param attrValue 属性值
     * @return 用户主键列表,已去重
     */
    public List<Long> searchUserIds(Long realmId, String attrKey, String attrValue) {
        return attributeIndexMapper.selectByRealmAndKey(realmId, attrKey, attrValue).stream()
                .map(UserAttributeIndex::getUserId)
                .distinct()
                .toList();
    }

    /**
     * 空属性 Map 便捷方法。
     *
     * @return 可写的空属性 Map
     */
    public static Map<String, Object> emptyAttributes() {
        return new LinkedHashMap<>();
    }
}
