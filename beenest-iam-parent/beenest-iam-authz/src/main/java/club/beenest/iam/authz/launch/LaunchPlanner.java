package club.beenest.iam.authz.launch;

import club.beenest.iam.persistence.entity.ApplicationService;
import org.springframework.stereotype.Component;

/**
 * 启动规划器:按应用配置算出这次点击该跳到哪里。
 * <p>门户启动的唯一判定来源,列表卡片与启动跳转都取自同一份 {@link LaunchPlan}。
 * 判定只依赖应用自身配置:凭据由应用与授权端点之间交换,门户不参与拼授权地址,
 * 也就不需要知道回调白名单、PKCE 要求与 scope。</p>
 * <p>"应用是否可见、是否启用"不在这里判:那是可见性服务的职责,重复判一次只会
 * 让两条规则各自漂移。</p>
 */
@Component
public class LaunchPlanner {

    /**
     * 生成启动计划。
     *
     * @param app 应用服务,可为 null
     * @return 启动计划,永不为 null
     */
    public LaunchPlan plan(ApplicationService app) {
        if (app == null) {
            return blocked(null, "应用不存在");
        }
        String entryUrl = trimToNull(app.getEntryUrl());
        if (entryUrl == null) {
            return blocked(app.getAppCode(), "应用未配置入口地址");
        }
        return new LaunchPlan(app.getAppCode(), entryUrl, null);
    }

    /**
     * 构造不可启动的计划。
     *
     * @param appCode 应用编码,可为 null
     * @param reason  不可启动的原因
     * @return 不可启动的计划
     */
    public static LaunchPlan blocked(String appCode, String reason) {
        return new LaunchPlan(appCode, null, reason);
    }

    /**
     * 去空白并归一空值为 null。
     *
     * @param value 原始值
     * @return 去空白后的值;入参为 null 或全空白时返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
