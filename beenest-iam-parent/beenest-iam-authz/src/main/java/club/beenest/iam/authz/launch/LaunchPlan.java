package club.beenest.iam.authz.launch;

/**
 * 启动计划:门户点击一张应用卡片后的判定结果。
 * <p>卡片状态与启动跳转共用同一份计划,避免两处各自推导导致卡片可点、
 * 跳转却失败。</p>
 *
 * @param appCode 应用编码
 * @param target  跳转目标(应用入口地址);不可启动时为 null
 * @param hint    不可启动的原因,可直接展示给用户与管理员;可启动时为 null
 */
public record LaunchPlan(
        String appCode,
        String target,
        String hint
) {

    /**
     * 是否无法启动。
     *
     * @return true 表示没有可跳转的目标地址
     */
    public boolean blocked() {
        return target == null;
    }
}
