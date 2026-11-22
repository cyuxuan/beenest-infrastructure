package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.admin.dto.ClientResponse;
import club.beenest.iam.admin.dto.ClientTypeChangeRequest;
import club.beenest.iam.admin.dto.ClientUpdateRequest;
import club.beenest.iam.common.result.PageResult;

/**
 * 客户端管理服务。
 * <p>操作范围限定在调用者所属身份域内。机密客户端的密钥明文
 * 只在创建与轮换的响应中出现一次,后续任何查询都不回显。</p>
 */
public interface ClientService {

    /**
     * 分页查询当前域客户端,不回显密钥。
     *
     * @param clientKey  客户端标识模糊匹配,可空
     * @param clientName 客户端名称模糊匹配,可空
     * @param clientType 类型精确匹配,可空
     * @param enabled    启用状态精确匹配,可空
     * @param pageNum    页码,从 1 开始
     * @param pageSize   每页条数
     * @return 客户端分页结果
     */
    PageResult<ClientResponse> page(String clientKey, String clientName, String clientType,
                                    Boolean enabled, int pageNum, int pageSize);

    /**
     * 查询单个客户端。
     *
     * @param id 客户端主键
     * @return 客户端详情,不含密钥明文
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    ClientResponse get(Long id);

    /**
     * 新建客户端。
     * <p>客户端标识域内唯一;创建时校验登出地址形态、凭据模式配置与准入登录方式白名单。</p>
     *
     * @param request 客户端创建参数
     * @return 新建后的客户端,机密客户端响应携带一次性明文密钥,公共客户端为空
     * @throws BusinessException 客户端标识重复或配置校验不通过
     */
    ClientResponse create(ClientCreateRequest request);

    /**
     * 更新客户端,仅覆盖请求中显式给出的字段。
     * <p>更新后仍需满足登出地址与准入白名单约束。</p>
     *
     * @param id      客户端主键
     * @param request 客户端更新参数
     * @return 更新后的客户端
     * @throws BusinessException 客户端不存在或更新后的配置校验不通过
     */
    ClientResponse update(Long id, ClientUpdateRequest request);

    /**
     * 逻辑删除客户端。
     * <p>删除前检查下游引用:授权同意、在线会话、客户端角色与绑定该客户端的应用服务。
     * 客户端自身的作用域分配随客户端一起清理,不计入引用。</p>
     *
     * @param id 客户端主键
     * @throws BusinessException 客户端不存在、不属于当前域,或仍被下游数据引用
     */
    void delete(Long id);

    /**
     * 变更客户端类型(机密 / 公开)。
     * <p>类型决定令牌端点的认证方式,不是普通属性,因此单独成动作而不并入通用更新:
     * 只有尚未投入使用的客户端允许变更。降级为公开客户端会清空密钥并强制 PKCE;
     * 升级为机密客户端会现场签发新密钥,明文只在本次响应中返回一次。</p>
     *
     * @param id      客户端主键
     * @param request 目标类型
     * @return 变更后的客户端;升级为机密客户端时响应携带一次性明文密钥
     * @throws BusinessException 客户端不存在、不属于当前域、已投入使用,或与凭据模式冲突
     */
    ClientResponse changeType(Long id, ClientTypeChangeRequest request);

    /**
     * 轮换机密客户端密钥。
     * <p>公共客户端不使用密钥。</p>
     *
     * @param id 客户端主键
     * @return 客户端详情,响应携带新的明文密钥,仅本次返回
     * @throws BusinessException 客户端不存在、不属于当前域或为公共客户端
     */
    ClientResponse rotateSecret(Long id);
}
