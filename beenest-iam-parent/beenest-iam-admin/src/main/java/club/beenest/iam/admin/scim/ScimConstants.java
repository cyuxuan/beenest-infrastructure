package club.beenest.iam.admin.scim;

/**
 * SCIM 2.0 协议常量。
 * <p>集中 schema URN 与媒体类型,避免字符串散落在控制器与服务里。</p>
 */
public final class ScimConstants {

    /** 用户资源 schema */
    public static final String SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    /** 组资源 schema */
    public static final String SCHEMA_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    /** 列表响应 schema */
    public static final String SCHEMA_LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    /** 错误响应 schema */
    public static final String SCHEMA_ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    /** 部分更新请求 schema */
    public static final String SCHEMA_PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** SCIM 资源媒体类型(RFC 7644 规定的 {@code application/scim+json}) */
    public static final String MEDIA_TYPE = "application/scim+json";

    /** 错误类型:唯一性冲突 */
    public static final String ERROR_UNIQUENESS = "uniqueness";
    /** 错误类型:取值非法 */
    public static final String ERROR_INVALID_VALUE = "invalidValue";
    /** 错误类型:过滤表达式非法 */
    public static final String ERROR_INVALID_FILTER = "invalidFilter";
    /** 错误类型:不支持的过滤属性 */
    public static final String ERROR_INVALID_PATH = "invalidPath";
    /** 错误类型:必填项缺失 */
    public static final String ERROR_INVALID_SYNTAX = "invalidSyntax";

    /** 资源类型:用户 */
    public static final String RESOURCE_USER = "User";
    /** 资源类型:组 */
    public static final String RESOURCE_GROUP = "Group";

    private ScimConstants() {
    }
}
