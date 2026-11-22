package club.beenest.iam.authz.requiredaction;

import club.beenest.iam.core.model.RequiredAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待办事项解析与序列化测试。
 * <p>这一层是库列与枚举之间的唯一转换点:解析宽松(丢弃未知代码)、
 * 序列化严格(只写本版本认识的代码),两边不对称是有意的。</p>
 */
class RequiredActionParseTest {

    @Test
    @DisplayName("空值与空白串解析为空集合")
    void parsesBlank() {
        assertTrue(RequiredAction.parse(null).isEmpty());
        assertTrue(RequiredAction.parse("").isEmpty());
        assertTrue(RequiredAction.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("逗号分隔多值,大小写与空白都容忍")
    void parsesMultiple() {
        Set<RequiredAction> actions = RequiredAction.parse(" update_password , VERIFY_EMAIL ");
        assertEquals(Set.of(RequiredAction.UPDATE_PASSWORD, RequiredAction.VERIFY_EMAIL), actions);
    }

    @Test
    @DisplayName("未知代码被丢弃:高版本写入的动作在降级后不该卡住用户")
    void dropsUnknownCodes() {
        Set<RequiredAction> actions = RequiredAction.parse("UPDATE_PASSWORD,CONFIGURE_WEBAUTHN");
        assertEquals(Set.of(RequiredAction.UPDATE_PASSWORD), actions);
    }

    @Test
    @DisplayName("序列化:空集合写空串,与列默认值一致")
    void joinsEmpty() {
        assertEquals("", RequiredAction.join(null));
        assertEquals("", RequiredAction.join(Set.of()));
    }

    @Test
    @DisplayName("序列化:多值按枚举顺序拼接,往返一致")
    void joinsAndParsesBack() {
        Set<RequiredAction> actions = new LinkedHashSet<>(
                List.of(RequiredAction.UPDATE_PASSWORD, RequiredAction.CONFIGURE_TOTP));
        String raw = RequiredAction.join(actions);
        assertEquals("UPDATE_PASSWORD,CONFIGURE_TOTP", raw);
        assertEquals(actions, RequiredAction.parse(raw));
    }

    @Test
    @DisplayName("单值解析:未知代码返回 null")
    void ofReturnsNullForUnknown() {
        assertNull(RequiredAction.of("NOT_A_REAL_ACTION"));
        assertNull(RequiredAction.of(""));
        assertEquals(RequiredAction.VERIFY_EMAIL, RequiredAction.of("verify_email"));
    }
}
