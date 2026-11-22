package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.PositionCreateRequest;
import club.beenest.iam.admin.dto.PositionResponse;
import club.beenest.iam.admin.dto.PositionRolesRequest;
import club.beenest.iam.admin.dto.PositionUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 岗位管理服务。
 * <p>岗位是人事语义的职务,角色是授权语义的权限集合,二者由岗位默认角色连接:
 * 用户任职某岗位即继承这些角色。</p>
 * <p>岗位不参与层级,也不自带权限,是角色到人的批量指派通道;
 * 岗位变更后权限代次抬升,域内权限缓存随之失效。</p>
 */
public interface PositionService {

    /**
     * 分页查询当前域岗位。
     *
     * @param positionCode 岗位编码模糊匹配,可空
     * @param positionName 岗位名称模糊匹配,可空
     * @param pageNum      页码,从 1 开始
     * @param pageSize     每页条数
     * @return 岗位分页结果,含角色数
     */
    PageResult<PositionResponse> page(String positionCode, String positionName, int pageNum, int pageSize);

    /**
     * 查询单个岗位。
     *
     * @param id 岗位主键
     * @return 岗位详情,含角色数
     * @throws BusinessException 岗位不存在或不属于当前域
     */
    PositionResponse get(Long id);

    /**
     * 新建岗位。
     * <p>岗位编码域内唯一,创建后不可修改。</p>
     *
     * @param request 岗位创建参数
     * @return 新建后的岗位
     * @throws BusinessException 岗位编码在域内已存在
     */
    PositionResponse create(PositionCreateRequest request);

    /**
     * 更新岗位,岗位编码不可修改,它是外部系统对齐岗位的依据。
     *
     * @param id      岗位主键
     * @param request 岗位更新参数
     * @return 更新后的岗位
     * @throws BusinessException 岗位不存在或不属于当前域
     */
    PositionResponse update(Long id, PositionUpdateRequest request);

    /**
     * 删除岗位,级联清理岗位角色关联,任职记录保留。
     *
     * @param id 岗位主键
     * @throws BusinessException 岗位不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 查询岗位已分配的角色。
     *
     * @param id 岗位主键
     * @return 角色列表
     * @throws BusinessException 岗位不存在或不属于当前域
     */
    List<RoleResponse> listRoles(Long id);

    /**
     * 覆盖式分配岗位角色。
     * <p>角色必须全部属于当前域;分配结果落到所有任职该岗位的用户。</p>
     *
     * @param id      岗位主键
     * @param request 角色主键集合
     * @throws BusinessException 岗位或角色不存在、角色跨域
     */
    void assignRoles(Long id, PositionRolesRequest request);
}
