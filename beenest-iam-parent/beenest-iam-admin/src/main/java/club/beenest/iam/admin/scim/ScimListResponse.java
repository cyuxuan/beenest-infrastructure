package club.beenest.iam.admin.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * SCIM 列表响应(RFC 7644 {@code ListResponse})。
 * <p>分页按 SCIM 口径:{@code startIndex} 从 1 开始,{@code itemsPerPage} 为本页条数,
 * {@code totalResults} 为满足条件的总数。调用方按这三项自行推算是否需要继续翻页。</p>
 *
 * @param schemas     固定为 ListResponse schema
 * @param totalResults 满足条件的总数
 * @param startIndex   本页起始序号(从 1 开始)
 * @param itemsPerPage 本页条数
 * @param resources    资源列表
 * @param <T>          资源类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimListResponse<T>(
        List<String> schemas,
        int totalResults,
        int startIndex,
        int itemsPerPage,
        @JsonProperty("Resources") List<T> resources) {

    /**
     * 构造列表响应。
     *
     * @param totalResults 总数
     * @param startIndex   起始序号
     * @param resources    本页资源
     * @param <T>          资源类型
     * @return 列表响应
     */
    public static <T> ScimListResponse<T> of(int totalResults, int startIndex, List<T> resources) {
        return new ScimListResponse<>(List.of(ScimConstants.SCHEMA_LIST), totalResults, startIndex,
                resources.size(), resources);
    }
}
