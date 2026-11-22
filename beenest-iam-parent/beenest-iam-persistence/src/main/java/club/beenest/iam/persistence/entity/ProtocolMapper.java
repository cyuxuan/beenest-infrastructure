package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 协议映射器实体(iam_protocol_mapper)。
 * <p>将用户属性或固定值映射为令牌 claim,client_id 为空表示域级默认映射。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProtocolMapper extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 客户端主键(为空表示域级) */
    private Long clientId;
    /** 映射器名称 */
    private String name;
    /** 目标 claim 名 */
    private String claimName;
    /** claim 类型:STRING/NUMBER/BOOLEAN/JSON */
    private String claimType;
    /** 用户属性键(userAttribute 与 claimTemplate 二选一) */
    private String userAttribute;
    /** 固定值模板 */
    private String claimTemplate;
}
