package club.beenest.iam.persistence.entity;

import lombok.Data;

/**
 * 客户端下游引用计数(非表实体,{@code countReferences} 的投影结果)。
 * <p>只统计"必须先解除才能安全删除"的引用:授权同意、客户端会话、客户端角色、
 * 绑定该客户端的应用服务。客户端自身的作用域分配属于它的配置,不计入引用。</p>
 */
@Data
public class ClientReferenceCount {

    /** 授权同意条数 */
    private Long consentCount;

    /** 在线客户端会话条数 */
    private Long sessionCount;

    /** 该客户端名下的角色条数 */
    private Long roleCount;

    /** 绑定该客户端的应用服务条数 */
    private Long appCount;

    /** 该客户端历史累计产生过的客户端会话条数(含已注销),用于判断是否已投入使用 */
    private Long everSessionCount;

    /**
     * 阻塞删除的引用总数。
     *
     * @return 四类阻塞引用之和;各项为空时按 0 计
     */
    public long blockingTotal() {
        return nullToZero(consentCount) + nullToZero(sessionCount)
                + nullToZero(roleCount) + nullToZero(appCount);
    }

    /**
     * 该客户端是否从未投入使用(从未产生客户端会话,也无授权同意记录)。
     *
     * @return 未投入使用时为 true
     */
    public boolean neverUsed() {
        return nullToZero(everSessionCount) == 0 && nullToZero(consentCount) == 0;
    }

    /**
     * 空值按 0 计。
     *
     * @param value 计数值,可为 null
     * @return 非空原值,空值返回 0
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
