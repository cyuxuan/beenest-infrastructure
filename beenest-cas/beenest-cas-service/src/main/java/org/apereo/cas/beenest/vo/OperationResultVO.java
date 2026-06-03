package org.apereo.cas.beenest.vo;

import lombok.Data;

import java.util.List;

/**
 * 通用操作结果响应 VO
 */
@Data
public final class OperationResultVO {

    /** 消息 */
    private String message;

    /** 操作关联的用户 ID */
    private String userId;

    /** 操作后的角色列表（角色变更时使用） */
    private List<String> roles;

    public static OperationResultVO of(String message, String userId) {
        var vo = new OperationResultVO();
        vo.setMessage(message);
        vo.setUserId(userId);
        return vo;
    }

    public static OperationResultVO of(String message) {
        return of(message, null);
    }
}