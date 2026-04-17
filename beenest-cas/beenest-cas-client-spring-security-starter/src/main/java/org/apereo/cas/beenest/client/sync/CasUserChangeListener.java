package org.apereo.cas.beenest.client.sync;

/**
 * 用户数据变更监听器
 * <p>
 * 应用服务可实现此接口，注册为 Spring Bean，
 * 在 CAS 推送的用户变更时执行自定义逻辑
 * （如更新本地用户表、清除缓存、发送通知等）。
 * <p>
 * 不实现此接口时，{@link CasSyncWebhookFilter} 仅自动刷新
 * Session 中的用户信息，应用服务零代码即可获得同步能力。
 * <p>
 * 使用示例：
 * <pre>
 * &#64;Component
 * public class MyUserChangeListener implements CasUserChangeListener {
 *     &#64;Override
 *     public void onUserChange(UserChangeEvent event) {
 *         // 更新本地用户缓存
 *         localUserService.updateFromCas(event.getUserId(), event.getNewData());
 *     }
 * }
 * </pre>
 */
@FunctionalInterface
public interface CasUserChangeListener {

    /**
     * 用户数据变更回调
     *
     * @param event 变更事件，包含变更类型、用户 ID、新数据
     */
    void onUserChange(UserChangeEvent event);
}
