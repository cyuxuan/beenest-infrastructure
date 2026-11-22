package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.ProtocolMapperCreateRequest;
import club.beenest.iam.admin.dto.ProtocolMapperResponse;
import club.beenest.iam.admin.dto.ProtocolMapperUpdateRequest;
import club.beenest.iam.common.result.PageResult;

/**
 * 协议映射器管理服务。
 * <p>把用户属性/固定值映射为令牌 claim;域级映射对全部客户端生效。</p>
 */
public interface ProtocolMapperService {

    /**
     * 分页查询当前域协议映射器。
     *
     * @param clientId 客户端主键,传入时查该客户端生效的映射,不传只查域级映射,可空
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     * @return 映射器分页结果
     * @throws BusinessException 指定客户端不存在或不属于当前域
     */
    PageResult<ProtocolMapperResponse> page(Long clientId, int pageNum, int pageSize);

    /**
     * 查询单个映射器。
     *
     * @param id 映射器主键
     * @return 映射器详情
     * @throws BusinessException 映射器不存在或不属于当前域
     */
    ProtocolMapperResponse get(Long id);

    /**
     * 新建映射器。
     * <p>userAttribute 与 claimTemplate 必须且只能提供一个;claim 类型限
     * STRING/NUMBER/BOOLEAN/JSON;客户端级映射必须挂靠本域客户端。</p>
     *
     * @param request 映射器创建参数
     * @return 新建后的映射器
     * @throws BusinessException 映射来源配置冲突、claim 类型非法或客户端跨域
     */
    ProtocolMapperResponse create(ProtocolMapperCreateRequest request);

    /**
     * 更新映射器。
     * <p>给出 userAttribute 或 claimTemplate 时互斥覆盖另一侧。</p>
     *
     * @param id      映射器主键
     * @param request 映射器更新参数
     * @return 更新后的映射器
     * @throws BusinessException 映射器不存在或 claim 类型非法
     */
    ProtocolMapperResponse update(Long id, ProtocolMapperUpdateRequest request);

    /**
     * 删除映射器。
     *
     * @param id 映射器主键
     * @throws BusinessException 映射器不存在或不属于当前域
     */
    void delete(Long id);
}
