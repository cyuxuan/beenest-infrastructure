package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.SamlServiceProviderRequest;
import club.beenest.iam.admin.dto.SamlServiceProviderResponse;

import java.util.List;
import java.util.Map;

/**
 * SAML 服务方登记管理。
 */
public interface SamlServiceProviderService {

    /**
     * 列出当前域全部登记。
     *
     * @return 登记列表;无记录返回空列表
     */
    List<SamlServiceProviderResponse> list();

    /**
     * 按主键读取登记。
     *
     * @param id 登记主键
     * @return 登记
     * @throws club.beenest.iam.common.exception.BusinessException 不存在或不属于当前域
     */
    SamlServiceProviderResponse get(Long id);

    /**
     * 新增登记。
     *
     * @param request 登记请求
     * @return 新增后的登记
     * @throws club.beenest.iam.common.exception.BusinessException 别名或实体 ID 重复、强制验签却未提供证书
     */
    SamlServiceProviderResponse create(SamlServiceProviderRequest request);

    /**
     * 修改登记。
     *
     * @param id      登记主键
     * @param request 登记请求
     * @return 修改后的登记
     * @throws club.beenest.iam.common.exception.BusinessException 登记不存在、别名或实体 ID 与他人重复
     */
    SamlServiceProviderResponse update(Long id, SamlServiceProviderRequest request);

    /**
     * 删除登记(逻辑删除)。
     *
     * @param id 登记主键
     * @throws club.beenest.iam.common.exception.BusinessException 登记不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 当前域的 IdP 端点地址,供管理员交给 SP 侧登记。
     *
     * @param origin 站点源地址
     * @return 实体 ID、单点登录、单点登出与元数据地址
     */
    Map<String, String> endpoints(String origin);
}
