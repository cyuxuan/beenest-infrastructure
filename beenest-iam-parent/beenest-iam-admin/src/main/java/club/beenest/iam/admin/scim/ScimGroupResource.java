package club.beenest.iam.admin.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SCIM 组资源(RFC 7644 {@code urn:ietf:params:scim:schemas:core:2.0:Group})。
 * <p>平台侧组即组织单元(部门/普通组),成员关系带岗位与主部门标记,SCIM 只表达成员集合,
 * 岗位由平台管理端维护——HR 同步不会覆盖岗位信息。</p>
 *
 * @param schemas    固定为 Group schema
 * @param id         平台组主键(字符串形式)
 * @param externalId 外部系统主键,存在组表的 {@code external_id}
 * @param displayName 组名称
 * @param members    成员列表
 * @param meta       资源元数据
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimGroupResource(
        List<String> schemas,
        String id,
        String externalId,
        String displayName,
        List<Member> members,
        Meta meta) {

    /**
     * 组成员。
     *
     * @param value   用户主键
     * @param display 用户显示名
     * @param type    成员类型,固定 User
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String value, String display, String type) {
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
