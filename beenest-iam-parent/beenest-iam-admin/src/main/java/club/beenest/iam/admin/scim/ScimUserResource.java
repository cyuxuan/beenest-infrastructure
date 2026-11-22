package club.beenest.iam.admin.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SCIM 用户资源(RFC 7644 {@code urn:ietf:params:scim:schemas:core:2.0:User})。
 * <p>只承载 HR 同步实际用到的属性:标识、登录名、显示名、邮箱、手机号、启用状态与所属组。
 * 平台侧的角色、权限与凭证不属于 SCIM 管辖范围,不在此暴露。</p>
 *
 * @param schemas      固定为 User schema
 * @param id           平台用户主键(字符串形式)
 * @param externalId   外部系统主键,存在用户扩展属性 {@code externalId} 上
 * @param userName     登录名,域内唯一
 * @param name         姓名对象,平台只有昵称,写入 {@code formatted}
 * @param displayName  显示名,取昵称
 * @param emails       邮箱列表,至多一条
 * @param phoneNumbers 手机号列表,至多一条
 * @param active       是否启用
 * @param groups       所属组(只读,由组成员关系推导)
 * @param meta         资源元数据
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimUserResource(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        Name name,
        String displayName,
        List<Email> emails,
        List<PhoneNumber> phoneNumbers,
        Boolean active,
        List<GroupRef> groups,
        Meta meta) {

    /**
     * 姓名。
     *
     * @param formatted 完整姓名
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Name(String formatted) {
    }

    /**
     * 邮箱。
     *
     * @param value   邮箱地址
     * @param type    类型,固定 work
     * @param primary 是否主邮箱
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Email(String value, String type, Boolean primary) {
    }

    /**
     * 手机号。
     *
     * @param value   手机号
     * @param type    类型,固定 mobile
     * @param primary 是否主号码
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhoneNumber(String value, String type, Boolean primary) {
    }

    /**
     * 组引用。
     *
     * @param value   组主键
     * @param display 组名称
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupRef(String value, String display) {
    }

    /**
     * 资源元数据。
     *
     * @param resourceType 资源类型
     * @param created      创建时间(RFC 3339 UTC)
     * @param lastModified 最后修改时间(RFC 3339 UTC)
     * @param location     资源地址
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String resourceType, String created, String lastModified, String location) {
    }
}
