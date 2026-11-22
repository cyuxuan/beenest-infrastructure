package club.beenest.iam.admin.dto;

/**
 * 邮件模板详情。
 *
 * @param code       模板编码
 * @param name       模板名称
 * @param overridden 是否已被本域覆盖
 * @param content    当前生效的模板原文(覆盖版本或内置版本)
 */
public record MailTemplateDetailResponse(
        String code,
        String name,
        boolean overridden,
        String content
) {
}
