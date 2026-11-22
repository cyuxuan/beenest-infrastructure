package club.beenest.iam.authz.saml;

import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.model.UserModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断言属性映射测试。
 * <p>重点在"默认不下发多余属性"与"取不到值的属性不出现":空值属性会让部分 SP
 * 建出空字符串字段,比缺字段更难排查。</p>
 */
class SamlIdpAttributeMapperTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "nestid";

    private final SamlIdpAttributeMapper mapper = new SamlIdpAttributeMapper();

    @Test
    void emptyMappingFallsBackToMinimalDefaults() {
        Map<String, List<String>> attributes = mapper.map(provider(Map.of()), user(), List.of("ADMIN"), REALM_KEY);

        assertEquals(List.of("alice"), attributes.get("username"));
        assertEquals(List.of("alice@corp.example.com"), attributes.get("email"));
        assertEquals(List.of("爱丽丝"), attributes.get("displayName"));
        // 默认不下发角色:每个属性都会被对端留存,默认多给一项就是多泄露一项
        assertFalse(attributes.containsKey("roles"));
    }

    @Test
    void explicitMappingOverridesDefaults() {
        Map<String, List<String>> attributes = mapper.map(
                provider(Map.of("mail", "email", "cn", "nickname")), user(), List.of(), REALM_KEY);

        assertEquals(List.of("alice@corp.example.com"), attributes.get("mail"));
        assertEquals(List.of("爱丽丝"), attributes.get("cn"));
        assertFalse(attributes.containsKey("email"));
    }

    @Test
    void rolesExpandToMultipleValues() {
        Map<String, List<String>> attributes = mapper.map(
                provider(Map.of("memberOf", "roles")), user(), List.of("ADMIN", "USER"), REALM_KEY);

        assertEquals(List.of("ADMIN", "USER"), attributes.get("memberOf"));
    }

    @Test
    void extendedAttributesArePassedThrough() {
        Map<String, List<String>> attributes = mapper.map(
                provider(Map.of("department", "attr.dept")), user(), List.of(), REALM_KEY);

        assertEquals(List.of("研发部"), attributes.get("department"));
    }

    @Test
    void missingValuesAreOmitted() {
        // 用户没有手机号,phone 表达式取不到值
        Map<String, List<String>> attributes = mapper.map(
                provider(Map.of("phone", "phone", "unknown", "no-such-expression")), user(), List.of(), REALM_KEY);

        assertTrue(attributes.isEmpty());
    }

    @Test
    void blankSourceIsIgnored() {
        Map<String, List<String>> attributes = mapper.map(
                provider(Map.of("empty", "  ")), user(), List.of(), REALM_KEY);

        assertTrue(attributes.isEmpty());
    }

    // ------------------------------------------------------------------ 夹具

    private static SamlServiceProviderModel provider(Map<String, String> mapping) {
        return new SamlServiceProviderModel(11L, REALM_ID, "sp-a", "https://sp.example.com/metadata",
                "示例应用", "https://sp.example.com/acs", "", "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified",
                true, false, "", mapping, true);
    }

    private static UserModel user() {
        return new UserModel(7L, REALM_ID, "alice", "爱丽丝", "alice@corp.example.com", null,
                1, "INTERNAL", true, 0, Map.of("dept", "研发部"));
    }
}
