package org.apereo.cas.beenest.client.sync;

import lombok.Data;

import java.util.Map;

/**
 * 用户数据变更事件
 * <p>
 * 当 CAS Server 推送用户变更 Webhook 时，
 * {@link CasSyncWebhookFilter} 解析请求体生成此事件，
 * 传递给 {@link CasUserChangeListener}。
 * <p>
 * 事件类型：
 * <ul>
 *   <li>CREATE — 用户注册</li>
 *   <li>UPDATE — 用户信息更新</li>
 *   <li>DELETE — 用户删除</li>
 *   <li>STATUS_CHANGE — 用户状态变更（启用/禁用/锁定）</li>
 * </ul>
 */
@Data
public class UserChangeEvent {

    /** 变更类型：CREATE / UPDATE / DELETE / STATUS_CHANGE */
    private String eventType;

    /** 用户 ID */
    private String userId;

    /** 变更后的用户数据（JSON 字符串或 Map） */
    private Map<String, Object> newData;

    /** CAS Server 推送时间 */
    private String timestamp;
}
