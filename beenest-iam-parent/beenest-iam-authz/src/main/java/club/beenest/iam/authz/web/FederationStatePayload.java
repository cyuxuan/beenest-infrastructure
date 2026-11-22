package club.beenest.iam.authz.web;

/**
 * 三方登录 state 的载荷。
 * <p>四段:{@code realmKey|alias|challengeToken|target},挑战令牌无值时保留空段。
 * 目标地址置于末段,split 有上限,地址中的分隔符不会切碎令牌;三段旧载荷仍可解析,挑战令牌为 null。</p>
 */
record FederationStatePayload(String realmKey, String alias, String challengeToken, String target) {

    /** 载荷分隔符 */
    static final String SEPARATOR = "|";

    /**
     * 拼成 state 载荷。
     *
     * @return 以 {@link #SEPARATOR} 连接的载荷字符串
     */
    String encode() {
        return String.join(SEPARATOR, realmKey, alias,
                challengeToken == null ? "" : challengeToken, target);
    }

    /**
     * 解析 state 载荷。
     * <p>三段为旧格式(无挑战令牌),四段为新格式;旧格式载荷只可能来自上线前的在途请求。</p>
     *
     * @param payload state 载荷字符串
     * @return 解析结果
     * @throws IllegalArgumentException 段数不足
     */
    static FederationStatePayload decode(String payload) {
        String[] parts = payload.split("\\" + SEPARATOR, 4);
        if (parts.length < 3) {
            throw new IllegalArgumentException("state 载荷格式非法");
        }
        if (parts.length == 3) {
            // 老格式:realmKey|alias|target
            return new FederationStatePayload(parts[0], parts[1], null, parts[2]);
        }
        String challengeToken = parts[2].isBlank() ? null : parts[2];
        return new FederationStatePayload(parts[0], parts[1], challengeToken, parts[3]);
    }
}
