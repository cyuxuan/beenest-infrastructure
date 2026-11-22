package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.ConsentRecordResponse;

import java.util.List;

/**
 * 授权同意管理服务。
 * <p>用户在某客户端上批准过的 scope 集合即同意记录;撤销后用户下次访问
 * 该客户端会重新看到授权确认页。</p>
 */
public interface ConsentAdminService {

    /**
     * 列举同意记录。
     * <p>仅返回客户端与用户都属于当前域的记录。</p>
     *
     * @param principalName 登录名模糊过滤,可空
     * @param clientId      客户端标识精确过滤,接受 client_key 或内部主键,可空
     * @return 扁平的同意记录列表
     */
    List<ConsentRecordResponse> list(String principalName, String clientId);

    /**
     * 撤销指定用户对指定客户端的同意,用户下次访问该客户端会重新看到授权确认页。
     *
     * @param clientId      client_key 或内部主键
     * @param principalName 用户登录名
     * @throws BusinessException 用户或客户端不存在、跨域,或同意记录不存在
     */
    void revoke(String clientId, String principalName);
}
