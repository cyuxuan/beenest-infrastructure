package club.beenest.iam.authz.authentication.step;

/**
 * 认证步骤所属阶段。
 * <p>阶段顺序即认证主干的执行顺序,编译期固定:前置校验 → 第一因素 → 状态校验 → 第二因素。
 * 新增步骤只能落在既有阶段内、通过 {@link AuthenticationStep#order()} 调整同阶段内的先后,
 * 不能改变阶段之间的顺序,因此认证流程始终可静态推断。</p>
 * <p>不提供"可配置阶段顺序":流程一旦可配置,排障就从读代码变成读配置快照,
 * 且配错能让全员登不进去而编译期毫无提示。</p>
 */
public enum Stage {

    /** 前置校验:身份域可用性、失败锁定、验证码门禁。失败即拒绝,不接触凭据。 */
    PRE_CHECK(10),

    /** 第一因素:证明"你是谁"。口令、目录 bind 及其它凭据方式在本阶段完成。 */
    FIRST_FACTOR(20),

    /** 状态校验:凭据通过后确认账号可登录(状态、账号类型),并清理失败计数。 */
    POST_CHECK(30),

    /** 第二因素:证明"还是你"。MFA 门禁与二步验证方式在本阶段判定。 */
    SECOND_FACTOR(40);

    /** 阶段序号,用于排序;同阶段内的步骤再按各自 order 排序 */
    private final int order;

    Stage(int order) {
        this.order = order;
    }

    /**
     * 阶段序号。
     *
     * @return 数值越小越先执行
     */
    public int order() {
        return order;
    }
}
