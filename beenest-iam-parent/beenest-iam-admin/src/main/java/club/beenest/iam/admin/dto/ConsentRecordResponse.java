package club.beenest.iam.admin.dto;

import java.util.Set;

/**
 * 授权同意记录响应。
 * <p>一条记录对应一个用户对某客户端批准过的 scope 集合。</p>
 *
 * @param clientId      客户端标识
 * @param principalName 同意主体登录名
 * @param authorities   已批准的 scope/权限
 */
public record ConsentRecordResponse(
        String clientId,
        String principalName,
        Set<String> authorities
) {
}
