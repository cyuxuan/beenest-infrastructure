package club.beenest.iam.admin.dto;

/**
 * 邮件模板摘要。
 *
 * @param code       模板编码
 * @param name       模板名称
 * @param overridden 是否已被本域覆盖
 */
public record MailTemplateSummaryResponse(
        String code,
        String name,
        boolean overridden
) {
}
