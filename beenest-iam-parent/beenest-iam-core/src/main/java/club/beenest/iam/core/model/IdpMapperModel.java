package club.beenest.iam.core.model;

/**
 * 身份提供方属性映射规则。
 * <p>回调时按 mapperType 搬运外部身份数据:</p>
 * <ul>
 *   <li><b>ATTRIBUTE</b>:外部 userinfo 中 sourceAttribute 的值写入本地用户的扩展属性 targetAttribute;</li>
 *   <li><b>ROLE</b>:外部角色集合包含 sourceAttribute 时,授予本地角色编码为 targetAttribute 的角色。</li>
 * </ul>
 *
 * @param id              主键
 * @param idpId           所属身份提供方主键
 * @param mapperType      映射类型:ATTRIBUTE / ROLE
 * @param sourceAttribute 外部属性名(ROLE 型为外部角色名)
 * @param targetAttribute 本地目标(ATTRIBUTE 型为扩展属性名,ROLE 型为本地角色编码)
 */
public record IdpMapperModel(
        Long id,
        Long idpId,
        String mapperType,
        String sourceAttribute,
        String targetAttribute
) {
    /** 映射类型:外部属性写入本地扩展属性 */
    public static final String TYPE_ATTRIBUTE = "ATTRIBUTE";
    /** 映射类型:外部角色映射为本地角色 */
    public static final String TYPE_ROLE = "ROLE";
}
