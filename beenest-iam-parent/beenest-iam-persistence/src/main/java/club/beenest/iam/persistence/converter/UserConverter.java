package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.persistence.entity.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 用户实体/模型转换器。
 * <p>扩展属性以 JSONB 文本存储,解析失败按空 Map 处理。</p>
 */
@Component
public class UserConverter {

    private final ObjectMapper objectMapper;

    public UserConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 实体转换为领域模型。
     *
     * @param user 用户实体
     * @return 用户模型;入参为 null 时返回 null
     */
    public UserModel toModel(User user) {
        if (user == null) {
            return null;
        }
        return new UserModel(
                user.getId(),
                user.getRealmId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getPhone(),
                user.getStatus() == null ? 1 : user.getStatus(),
                user.getUserType(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                parseAttributes(user.getAttributes()),
                RequiredAction.parse(user.getRequiredActions())
        );
    }

    /**
     * 解析扩展属性 JSON。
     *
     * @param json 扩展属性 JSON 文本
     * @return 属性 Map;空值或非法内容返回空 Map
     */
    public Map<String, String> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 扩展属性 Map 序列化为 JSONB 文本。
     *
     * @param attributes 属性 Map
     * @return JSON 文本;空值输出空对象,保证列非空
     */
    public String toAttributeJson(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception e) {
            throw new IllegalStateException("扩展属性序列化失败", e);
        }
    }
}
