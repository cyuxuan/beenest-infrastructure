package club.beenest.iam.admin.scim;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * SCIM 部分更新请求({@code urn:ietf:params:scim:api:messages:2.0:PatchOp})。
 * <p>HR 系统同步人员与组织时主要用 PATCH:只提交变化的属性,不会因为没带全字段
 * 而清空其它数据。因此本平台把 PATCH 作为主路径支持,PUT 只作为兼容补充。</p>
 *
 * @param schemas    固定为 PatchOp schema
 * @param operations 操作列表
 */
public record ScimPatchRequest(
        List<String> schemas,
        @JsonAlias("operations") List<Operation> operations) {

    /**
     * 单个操作。
     * <p>{@code path} 可空:为空时 {@code value} 是一份属性对象,按对象内出现的属性逐项替换。</p>
     *
     * @param op    操作类型:add / replace / remove
     * @param path  目标属性路径,可空
     * @param value 目标值,可为标量、数组或对象
     */
    public record Operation(String op, String path, Object value) {
    }
}
