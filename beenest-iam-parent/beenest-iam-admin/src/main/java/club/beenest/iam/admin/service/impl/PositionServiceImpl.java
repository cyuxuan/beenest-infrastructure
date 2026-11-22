package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.PositionCreateRequest;
import club.beenest.iam.admin.dto.PositionResponse;
import club.beenest.iam.admin.dto.PositionRolesRequest;
import club.beenest.iam.admin.dto.PositionUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.service.PositionService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 岗位管理实现。
 * <p>岗位与任职关系限定在当前身份域;岗位变更后由持久层抬升权限代次,域内权限缓存随之失效。</p>
 */
@Service
public class PositionServiceImpl implements PositionService {

    private final IamPositionManager positionManager;
    private final IamRoleManager roleManager;
    private final RoleDtoConverter roleDtoConverter;

    public PositionServiceImpl(IamPositionManager positionManager, IamRoleManager roleManager,
                               RoleDtoConverter roleDtoConverter) {
        this.positionManager = positionManager;
        this.roleManager = roleManager;
        this.roleDtoConverter = roleDtoConverter;
    }

    @Override
    public PageResult<PositionResponse> page(String positionCode, String positionName,
                                            int pageNum, int pageSize) {
        Position condition = new Position();
        condition.setPositionCode(positionCode);
        condition.setPositionName(positionName);
        PageResult<Position> page = positionManager.page(currentRealmId(), condition, pageNum, pageSize);
        // 角色数一次批量算完
        Map<Long, Long> roleCounts = positionManager.countRoles(
                page.getRecords().stream().map(Position::getId).toList());
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream()
                        .map(position -> toResponse(position, roleCounts.getOrDefault(position.getId(), 0L)))
                        .toList());
    }

    @Override
    public PositionResponse get(Long id) {
        Position position = requirePositionInRealm(id);
        return toResponse(position, positionManager.listRoles(id).size());
    }

    @Override
    public PositionResponse create(PositionCreateRequest request) {
        Long realmId = currentRealmId();
        if (positionManager.getByCode(realmId, request.positionCode()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "岗位编码已存在: " + request.positionCode());
        }
        Position position = new Position();
        position.setRealmId(realmId);
        position.setPositionCode(request.positionCode());
        position.setPositionName(request.positionName());
        position.setPositionLevel(request.positionLevel() == null ? "" : request.positionLevel());
        position.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        positionManager.create(position);
        return get(position.getId());
    }

    @Override
    public PositionResponse update(Long id, PositionUpdateRequest request) {
        Position position = requirePositionInRealm(id);
        if (request.positionName() != null) {
            position.setPositionName(request.positionName());
        }
        if (request.positionLevel() != null) {
            position.setPositionLevel(request.positionLevel());
        }
        if (request.sortOrder() != null) {
            position.setSortOrder(request.sortOrder());
        }
        positionManager.update(position);
        return get(id);
    }

    @Override
    public void delete(Long id) {
        requirePositionInRealm(id);
        // 级联清理岗位角色关联,任职记录保留
        positionManager.remove(id);
    }

    @Override
    public List<RoleResponse> listRoles(Long id) {
        requirePositionInRealm(id);
        return positionManager.listRoles(id).stream()
                .map(roleDtoConverter::toResponse)
                .toList();
    }

    @Override
    public void assignRoles(Long id, PositionRolesRequest request) {
        Position position = requirePositionInRealm(id);
        Long realmId = currentRealmId();
        // 角色必须全部属于当前域,岗位角色会落到所有任职该岗位的人身上
        for (Long roleId : request.roleIds()) {
            Role role = roleManager.getById(roleId);
            if (role == null || !realmId.equals(role.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在: " + roleId);
            }
        }
        positionManager.assignRoles(realmId, position.getId(), request.roleIds(),
                currentPrincipal().getUserId());
    }

    /**
     * 岗位转响应。
     *
     * @param position  岗位
     * @param roleCount 角色数
     * @return 岗位响应
     */
    private PositionResponse toResponse(Position position, long roleCount) {
        return new PositionResponse(position.getId(), position.getPositionCode(),
                position.getPositionName(), position.getPositionLevel(), position.getSortOrder(),
                roleCount, position.getCreatedTime());
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return currentPrincipal().getRealmId();
    }

    /**
     * 当前调用者主体。
     *
     * @return 用户主体
     */
    private IamUserPrincipal currentPrincipal() {
        return CurrentUserHolder.require();
    }

    /**
     * 读取属于当前域的岗位。
     *
     * @param id 岗位主键
     * @return 岗位
     * @throws BusinessException 岗位不存在或不属于当前域
     */
    private Position requirePositionInRealm(Long id) {
        Position position = positionManager.getById(id);
        if (position == null || !currentRealmId().equals(position.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在: " + id);
        }
        return position;
    }
}
