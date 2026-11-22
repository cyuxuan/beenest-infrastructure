package club.beenest.iam.authz.saml;

import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.model.UserModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断言属性映射:把本地账号的字段翻译成 SP 登记的属性名。
 * <p>取值表达式是一组固定关键字,加 {@code attr.} 前缀可透传用户扩展属性。
 * 未配置映射时只下发登录名、邮箱与显示名三项:断言里的每一个属性都会被对端留存,
 * 默认多给一项就是多泄露一项。</p>
 */
@Component
public class SamlIdpAttributeMapper {

    /** 取值表达式前缀:透传用户扩展属性 */
    private static final String ATTRIBUTE_PREFIX = "attr.";

    /** 未配置映射时的默认属性:属性名 → 取值表达式 */
    private static final Map<String, String> DEFAULT_MAPPING = new LinkedHashMap<>();

    static {
        DEFAULT_MAPPING.put("username", "username");
        DEFAULT_MAPPING.put("email", "email");
        DEFAULT_MAPPING.put("displayName", "nickname");
    }

    /**
     * 生成断言属性。
     *
     * @param provider   SP 登记项,提供属性映射
     * @param user       用户
     * @param roleCodes  用户角色码;为 {@code roles} 表达式提供多值
     * @param realmKey   身份域标识
     * @return 属性名 → 值列表;取不到值的属性不出现在结果里
     */
    public Map<String, List<String>> map(SamlServiceProviderModel provider, UserModel user,
                                         List<String> roleCodes, String realmKey) {
        Map<String, String> mapping = provider.attributeMapping();
        if (mapping == null || mapping.isEmpty()) {
            mapping = DEFAULT_MAPPING;
        }
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        mapping.forEach((name, source) -> {
            List<String> values = resolve(source, user, roleCodes, realmKey);
            if (!values.isEmpty()) {
                attributes.put(name, values);
            }
        });
        return attributes;
    }

    /**
     * 按取值表达式求值。
     *
     * @param source    取值表达式
     * @param user      用户
     * @param roleCodes 用户角色码
     * @param realmKey  身份域标识
     * @return 值列表;表达式无法识别或取不到值时返回空列表
     */
    private List<String> resolve(String source, UserModel user, List<String> roleCodes, String realmKey) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        if (source.startsWith(ATTRIBUTE_PREFIX)) {
            return single(user.attributes().get(source.substring(ATTRIBUTE_PREFIX.length())));
        }
        return switch (source) {
            case "username" -> single(user.username());
            case "nickname" -> single(user.nickname());
            case "email" -> single(user.email());
            case "phone" -> single(user.phone());
            case "userId" -> single(user.id() == null ? null : String.valueOf(user.id()));
            case "realm" -> single(realmKey);
            // 角色是多值属性:SAML 的 Attribute 本身就支持多个 AttributeValue
            case "roles" -> roleCodes == null ? List.of() : List.copyOf(roleCodes);
            default -> List.of();
        };
    }

    /**
     * 单值包装:空白值视为未取到。
     *
     * @param value 值
     * @return 只含该值的列表;值为空时返回空列表
     */
    private static List<String> single(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(1);
        values.add(value);
        return values;
    }
}
