package club.beenest.iam.admin.dto;

/**
 * 角色映射来源候选。
 * <p>仅包含主键、编码与展示名,用于映射来源选择。</p>
 *
 * @param id   来源主体主键
 * @param code 组编码或角色编码
 * @param name 展示名称
 */
public record MappingSourceResponse(Long id, String code, String name) {
}
