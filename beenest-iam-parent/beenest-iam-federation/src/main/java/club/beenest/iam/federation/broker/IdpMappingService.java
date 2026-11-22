package club.beenest.iam.federation.broker;

import club.beenest.iam.core.model.IdpMapperModel;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 身份提供方属性映射执行器。
 * <p>在回调换取身份后,按提供方配置的映射规则生成本地扩展属性与本地角色编码。</p>
 * <p>两类规则:</p>
 * <ul>
 *   <li><b>ATTRIBUTE</b>:取外部 userinfo 的 source_attribute,写入本地扩展属性 target_attribute;</li>
 *   <li><b>ROLE</b>:外部角色集合命中 source_attribute 时,授予 target_attribute 指定的本地角色编码。</li>
 * </ul>
 * <p>外部角色集合取自 userinfo 的 {@code roles},缺失时取 {@code groups};
 * 支持数组与逗号分隔字符串两种形态。</p>
 */
@Component
public class IdpMappingService {

    /** 外部角色集合的候选属性名(按顺序取第一个存在的) */
    private static final String[] EXTERNAL_ROLE_KEYS = {"roles", "groups"};

    /**
     * 按映射规则算出本地扩展属性与本地角色编码。
     *
     * @param identity 回调换回的外部身份(含原始属性)
     * @param mappers  提供方配置的映射规则
     * @return 映射结果
     */
    public MappingResult apply(BrokeredIdentityContext identity, List<IdpMapperModel> mappers) {
        // 1. 本地扩展属性:在外部属性副本上改写,不动原始上下文
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>(identity.getAttributes());
        // 2. 本地角色编码
        Set<String> roleCodes = new LinkedHashSet<>();
        if (mappers != null) {
            Set<String> externalRoles = externalRoles(identity);
            for (IdpMapperModel mapper : mappers) {
                if (mapper == null || mapper.sourceAttribute() == null || mapper.sourceAttribute().isBlank()) {
                    continue;
                }
                if (mapper.targetAttribute() == null || mapper.targetAttribute().isBlank()) {
                    continue;
                }
                if (IdpMapperModel.TYPE_ROLE.equalsIgnoreCase(mapper.mapperType())) {
                    // 角色映射:外部角色集合命中即视为命中
                    if (externalRoles.contains(mapper.sourceAttribute().trim())) {
                        roleCodes.add(mapper.targetAttribute().trim());
                    }
                } else {
                    // 属性映射:取外部属性值写入本地扩展属性
                    String value = attributes.get(mapper.sourceAttribute().trim());
                    if (value != null && !value.isBlank()) {
                        attributes.put(mapper.targetAttribute().trim(), value);
                    }
                }
            }
        }
        return new MappingResult(attributes, new ArrayList<>(roleCodes));
    }

    /**
     * 解析外部角色集合。
     * <p>支持 JSON 数组与逗号分隔字符串两种形态,按 {@code roles}、{@code groups} 顺序取第一个非空值。</p>
     *
     * @param identity 外部身份上下文
     * @return 外部角色集合
     */
    private static Set<String> externalRoles(BrokeredIdentityContext identity) {
        Set<String> roles = new LinkedHashSet<>();
        for (String key : EXTERNAL_ROLE_KEYS) {
            String raw = identity.getAttributes().get(key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            // JSON 数组形态:["admin","staff"]
            if (trimmed.startsWith("[")) {
                trimmed = trimmed.substring(1, trimmed.endsWith("]") ? trimmed.length() - 1 : trimmed.length());
            }
            for (String part : trimmed.split(",")) {
                String role = part.trim().replace("\"", "");
                if (!role.isEmpty()) {
                    roles.add(role);
                }
            }
            if (!roles.isEmpty()) {
                break;
            }
        }
        return roles;
    }

    /**
     * 映射结果。
     *
     * @param attributes 本地扩展属性
     * @param roleCodes  应授予的本地角色编码
     */
    public record MappingResult(java.util.Map<String, String> attributes, List<String> roleCodes) {
    }
}
