package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.RealmCreateRequest;
import club.beenest.iam.admin.dto.RealmKeyResponse;
import club.beenest.iam.admin.dto.RealmResponse;
import club.beenest.iam.admin.dto.RealmUpdateRequest;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 身份域管理服务。
 * <p>域标识全局唯一且创建后不可变更;删除为逻辑删除,默认域不允许删除。</p>
 */
public interface RealmService {

    /**
     * 分页查询身份域。
     *
     * @param realmKey  域标识精确匹配,可空
     * @param realmName 域名称模糊匹配,可空
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 身份域分页结果
     */
    PageResult<RealmResponse> page(String realmKey, String realmName, int pageNum, int pageSize);

    /**
     * 查询单个身份域。
     *
     * @param id 身份域主键
     * @return 身份域详情
     * @throws BusinessException 身份域不存在
     */
    RealmResponse get(Long id);

    /**
     * 新建身份域。
     * <p>域标识全局唯一,逻辑删除的域不占用标识;创建时同步初始化登录与口令策略行。</p>
     *
     * @param request 身份域创建参数
     * @return 新建后的身份域
     * @throws BusinessException 域标识已存在或主题目录不存在
     */
    RealmResponse create(RealmCreateRequest request);

    /**
     * 更新身份域,仅覆盖请求中显式给出的字段;域标识不可变更。
     *
     * @param id      身份域主键
     * @param request 身份域更新参数
     * @return 更新后的身份域
     * @throws BusinessException 身份域不存在或主题目录不存在
     */
    RealmResponse update(Long id, RealmUpdateRequest request);

    /**
     * 逻辑删除身份域,域下用户、角色等数据保持可追溯。
     * <p>默认域是登录页与协议端点的兜底入口,不允许删除。</p>
     *
     * @param id 身份域主键
     * @throws BusinessException 身份域不存在或为默认域
     */
    void delete(Long id);

    /**
     * 查询域的签名密钥列表,不含密钥材料。
     * <p>轮换后旧密钥仍在列表中,JWKS 继续发布其公钥供已签发令牌验签过渡。</p>
     *
     * @param id 身份域主键
     * @return 密钥生命周期信息列表
     * @throws BusinessException 身份域不存在
     */
    List<RealmKeyResponse> listKeys(Long id);

    /**
     * 轮换域签名密钥:退役旧密钥并生成新密钥。
     * <p>已签发令牌不受影响,新签发全部改用新密钥;属高危运维动作,应低频执行并留下审计记录。</p>
     *
     * @param id 身份域主键
     * @throws BusinessException 身份域不存在
     */
    void rotateKey(Long id);

    /**
     * 查询可用的协议页主题目录名。
     * <p>主题是 classpath 下的静态资源目录,启动时扫描,新增主题重启后生效。</p>
     *
     * @return 主题目录名列表
     */
    List<String> listThemes();
}
