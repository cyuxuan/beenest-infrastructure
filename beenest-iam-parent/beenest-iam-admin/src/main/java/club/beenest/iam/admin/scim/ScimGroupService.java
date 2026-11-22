package club.beenest.iam.admin.scim;

import club.beenest.iam.admin.dto.GroupCreateRequest;
import club.beenest.iam.admin.dto.GroupMemberAssignment;
import club.beenest.iam.admin.dto.GroupMemberResponse;
import club.beenest.iam.admin.dto.GroupMembersRequest;
import club.beenest.iam.admin.dto.GroupResponse;
import club.beenest.iam.admin.dto.GroupUpdateRequest;
import club.beenest.iam.admin.service.GroupService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * SCIM 组资源服务:把 SCIM 组报文与平台用户组模型互转。
 * <p>组在平台侧同时承担"组织单元"与"权限主体"两种角色,SCIM 只同步结构与成员,
 * 组上的角色授权不通过 SCIM 变更——否则 HR 一次全量同步就可能改掉权限。</p>
 */
@Service
public class ScimGroupService {

    /** 读取现有成员任职时的单页上限;超过该规模的组请走管理端维护 */
    private static final int MEMBER_PAGE_SIZE = 10000;
    /** 按外部主键检索时的最大扫描条数 */
    private static final int MAX_SCAN = 1000;
    /** 单页扫描条数 */
    private static final int SCAN_PAGE_SIZE = 100;

    private final GroupService groupService;

    public ScimGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * 分页查询组。
     *
     * @param filter     过滤表达式,可为 null
     * @param startIndex 起始序号,从 1 开始
     * @param count      本页条数
     * @param baseUrl    资源地址前缀
     * @return 列表响应
     * @throws ScimException 过滤属性不支持
     */
    public ScimListResponse<ScimGroupResource> list(ScimFilter filter, int startIndex, int count, String baseUrl) {
        int pageSize = Math.max(1, Math.min(count, 100));
        int pageNum = startIndex <= 1 ? 1 : 1 + (startIndex - 1) / pageSize;
        if (filter == null) {
            PageResult<GroupResponse> page = groupService.page(null, null, pageNum, pageSize);
            return ScimListResponse.of((int) page.getTotal(), startIndex,
                    page.getRecords().stream().map(group -> toResource(group, null, baseUrl)).toList());
        }
        if (filter.matchesAttribute("displayName")) {
            PageResult<GroupResponse> page = groupService.page(null, filter.value(), 1, SCAN_PAGE_SIZE);
            List<GroupResponse> matched = page.getRecords().stream()
                    .filter(group -> filter.test(group.groupName()))
                    .toList();
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().limit(pageSize).map(group -> toResource(group, null, baseUrl)).toList());
        }
        if (filter.matchesAttribute("externalId")) {
            List<GroupResponse> matched = scanByExternalId(filter);
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().limit(pageSize).map(group -> toResource(group, null, baseUrl)).toList());
        }
        if (filter.matchesAttribute("id")) {
            GroupResponse group = findById(filter.value());
            List<GroupResponse> matched = group != null && filter.test(String.valueOf(group.id()))
                    ? List.of(group) : List.of();
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().map(item -> toResource(item, null, baseUrl)).toList());
        }
        throw ScimException.badRequest(ScimConstants.ERROR_INVALID_FILTER,
                "不支持的过滤属性: " + filter.attribute() + "(可用: displayName / externalId / id)");
    }

    /**
     * 查询单个组,含成员列表。
     *
     * @param id      组主键
     * @param baseUrl 资源地址前缀
     * @return 组资源
     * @throws ScimException 组不存在
     */
    public ScimGroupResource get(String id, String baseUrl) {
        GroupResponse group = requireGroup(id);
        return toResource(group, membersOf(group.id()), baseUrl);
    }

    /**
     * 新建组。
     * <p>SCIM 没有"组编码"概念,平台要求域内唯一编码:优先取 {@code externalId},
     * 缺失时生成 {@code scim-} 前缀的随机编码。HR 系统应始终提供 {@code externalId},
     * 否则重建同名组会得到两个编码不同的组。</p>
     *
     * @param resource 组资源
     * @param baseUrl  资源地址前缀
     * @return 新建后的组资源
     * @throws ScimException 缺少组名、编码重复或取值非法
     */
    public ScimGroupResource create(ScimGroupResource resource, String baseUrl) {
        String displayName = trimToNull(resource.displayName());
        if (displayName == null) {
            throw ScimException.badRequest(ScimConstants.ERROR_INVALID_SYNTAX, "displayName 不能为空");
        }
        String externalId = trimToNull(resource.externalId());
        String groupCode = externalId != null ? externalId
                : "scim-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        GroupResponse created = translate(() -> groupService.create(
                new GroupCreateRequest(null, groupCode, displayName, null, null, null)));
        if (resource.members() != null && !resource.members().isEmpty()) {
            assignMembers(created.id(), toAssignments(memberIdsOf(resource.members())));
        }
        GroupResponse reloaded = translate(() -> groupService.get(created.id()));
        return toResource(reloaded, membersOf(created.id()), baseUrl);
    }

    /**
     * 整体替换组属性(PUT)。
     * <p>只更新报文里出现的属性;成员列表出现时按整体覆盖处理(SCIM 的 replace 语义)。</p>
     *
     * @param id       组主键
     * @param resource 组资源
     * @param baseUrl  资源地址前缀
     * @return 更新后的组资源
     * @throws ScimException 组不存在或取值非法
     */
    public ScimGroupResource replace(String id, ScimGroupResource resource, String baseUrl) {
        GroupResponse existing = requireGroup(id);
        String displayName = trimToNull(resource.displayName());
        if (displayName != null) {
            translate(() -> groupService.update(existing.id(),
                    new GroupUpdateRequest(null, displayName, null, null, null, null, null)));
        }
        if (resource.members() != null) {
            assignMembers(existing.id(), toAssignments(memberIdsOf(resource.members())));
        }
        GroupResponse reloaded = translate(() -> groupService.get(existing.id()));
        return toResource(reloaded, membersOf(existing.id()), baseUrl);
    }

    /**
     * 部分更新组属性(PATCH)。
     * <p>支持 {@code displayName} 与 {@code members} 两个路径,操作类型 add / replace / remove。
     * 成员变更在现有任职基础上做增删,已有成员的岗位与主部门标记原样保留。</p>
     *
     * @param id      组主键
     * @param request 部分更新请求
     * @param baseUrl 资源地址前缀
     * @return 更新后的组资源
     * @throws ScimException 组不存在、路径不支持或取值非法
     */
    public ScimGroupResource patch(String id, ScimPatchRequest request, String baseUrl) {
        GroupResponse existing = requireGroup(id);
        if (request == null || request.operations() == null || request.operations().isEmpty()) {
            throw ScimException.badRequest(ScimConstants.ERROR_INVALID_SYNTAX, "Operations 不能为空");
        }
        List<GroupMemberAssignment> assignments = currentAssignments(existing.id());
        boolean membersTouched = false;
        for (ScimPatchRequest.Operation operation : request.operations()) {
            String path = operation.path() == null ? "" : operation.path().replace(" ", "")
                    .toLowerCase(Locale.ROOT);
            String op = operation.op() == null ? "" : operation.op().toLowerCase(Locale.ROOT);
            switch (path) {
                case "displayname", "name" -> {
                    String value = scalarOf(operation.value());
                    translate(() -> groupService.update(existing.id(),
                            new GroupUpdateRequest(null, value, null, null, null, null, null)));
                }
                case "members" -> {
                    List<Long> targets = memberIdsOf(operation.value());
                    assignments = switch (op) {
                        case "remove" -> withoutMembers(assignments, targets);
                        case "add" -> withMembers(assignments, targets);
                        default -> toAssignments(targets);
                    };
                    membersTouched = true;
                }
                default -> throw ScimException.badRequest(ScimConstants.ERROR_INVALID_PATH,
                        "不支持的属性路径: " + operation.path());
            }
        }
        if (membersTouched) {
            assignMembers(existing.id(), assignments);
        }
        GroupResponse reloaded = translate(() -> groupService.get(existing.id()));
        return toResource(reloaded, membersOf(existing.id()), baseUrl);
    }

    /**
     * 删除组(子组自动提升到被删组的父级)。
     *
     * @param id 组主键
     * @throws ScimException 组不存在
     */
    public void delete(String id) {
        GroupResponse existing = requireGroup(id);
        translate(() -> {
            groupService.delete(existing.id());
            return null;
        });
    }

    /**
     * 读取组的现有任职集合。
     *
     * @param groupId 组主键
     * @return 任职集合;无成员时返回空列表
     */
    private List<GroupMemberAssignment> currentAssignments(Long groupId) {
        PageResult<GroupMemberResponse> page =
                groupService.pageMembers(groupId, 1, MEMBER_PAGE_SIZE, false);
        return page.getRecords().stream()
                .map(member -> new GroupMemberAssignment(member.userId(), member.positionId(), member.primary()))
                .toList();
    }

    /**
     * 写入成员任职。
     *
     * @param groupId     组主键
     * @param assignments 目标任职集合
     */
    private void assignMembers(Long groupId, List<GroupMemberAssignment> assignments) {
        translate(() -> {
            groupService.assignMembers(groupId, new GroupMembersRequest(assignments));
            return null;
        });
    }

    /**
     * 合并新增成员,已存在的成员保留原有岗位与主部门标记。
     *
     * @param current 现有任职
     * @param targets 需要新增的成员主键
     * @return 合并后的任职
     */
    private static List<GroupMemberAssignment> withMembers(List<GroupMemberAssignment> current, List<Long> targets) {
        Set<Long> existing = new LinkedHashSet<>();
        current.forEach(item -> existing.add(item.userId()));
        List<GroupMemberAssignment> merged = new ArrayList<>(current);
        for (Long userId : targets) {
            if (existing.add(userId)) {
                merged.add(new GroupMemberAssignment(userId, null, Boolean.FALSE));
            }
        }
        return merged;
    }

    /**
     * 移除指定成员。
     *
     * @param current 现有任职
     * @param targets 需要移除的成员主键
     * @return 移除后的任职
     */
    private static List<GroupMemberAssignment> withoutMembers(List<GroupMemberAssignment> current,
                                                              List<Long> targets) {
        return current.stream().filter(item -> !targets.contains(item.userId())).toList();
    }

    /**
     * 由成员主键构造任职集合(整体替换时使用)。
     *
     * @param targets 成员主键
     * @return 任职集合
     */
    private static List<GroupMemberAssignment> toAssignments(List<Long> targets) {
        return targets.stream()
                .distinct()
                .map(userId -> new GroupMemberAssignment(userId, null, Boolean.FALSE))
                .toList();
    }

    /**
     * 从报文值解析成员主键。
     *
     * @param value 操作值,可能是成员对象数组,也可能是主键字符串
     * @return 成员主键列表;无法解析的项被忽略
     */
    private static List<Long> memberIdsOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            Object raw = item instanceof java.util.Map<?, ?> map ? map.get("value") : item;
            try {
                ids.add(Long.parseLong(String.valueOf(raw)));
            } catch (NumberFormatException e) {
                throw ScimException.badRequest(ScimConstants.ERROR_INVALID_VALUE,
                        "成员主键必须是数字: " + raw);
            }
        }
        return ids;
    }

    /**
     * 从资源里解析成员主键。
     *
     * @param members 成员列表
     * @return 成员主键列表
     */
    private static List<Long> memberIdsOf(List<ScimGroupResource.Member> members) {
        List<Long> ids = new ArrayList<>();
        for (ScimGroupResource.Member member : members) {
            if (member == null || member.value() == null) {
                continue;
            }
            try {
                ids.add(Long.parseLong(member.value()));
            } catch (NumberFormatException e) {
                throw ScimException.badRequest(ScimConstants.ERROR_INVALID_VALUE,
                        "成员主键必须是数字: " + member.value());
            }
        }
        return ids;
    }

    /**
     * 读取组成员引用。
     *
     * @param groupId 组主键
     * @return 成员列表;无成员时返回空列表
     */
    private List<ScimGroupResource.Member> membersOf(Long groupId) {
        PageResult<GroupMemberResponse> page =
                groupService.pageMembers(groupId, 1, MEMBER_PAGE_SIZE, false);
        return page.getRecords().stream()
                .map(member -> new ScimGroupResource.Member(String.valueOf(member.userId()),
                        member.nickname() == null ? member.username() : member.nickname(),
                        ScimConstants.RESOURCE_USER))
                .toList();
    }

    /**
     * 按外部主键扫描匹配组。
     *
     * @param filter 过滤表达式
     * @return 命中的组列表
     */
    private List<GroupResponse> scanByExternalId(ScimFilter filter) {
        List<GroupResponse> matched = new ArrayList<>();
        int pageNum = 1;
        int scanned = 0;
        while (scanned < MAX_SCAN) {
            PageResult<GroupResponse> page = groupService.page(null, null, pageNum, SCAN_PAGE_SIZE);
            for (GroupResponse group : page.getRecords()) {
                scanned++;
                if (filter.test(group.externalId())) {
                    matched.add(group);
                }
            }
            if (page.getRecords().size() < SCAN_PAGE_SIZE) {
                break;
            }
            pageNum++;
        }
        return matched;
    }

    /**
     * 按主键查组。
     *
     * @param id 主键字符串
     * @return 组;主键非法或不存在时返回 null
     */
    private GroupResponse findById(String id) {
        try {
            return groupService.get(Long.parseLong(id));
        } catch (NumberFormatException | BusinessException e) {
            return null;
        }
    }

    /**
     * 读取组,不存在时抛出 SCIM 404。
     *
     * @param id 主键字符串
     * @return 组
     * @throws ScimException 主键非法或组不存在
     */
    private GroupResponse requireGroup(String id) {
        GroupResponse group = findById(id);
        if (group == null) {
            throw ScimException.notFound("组不存在: " + id);
        }
        return group;
    }

    /**
     * 组模型转 SCIM 资源。
     *
     * @param group   组
     * @param members 成员列表,为 null 时不输出成员字段
     * @param baseUrl 资源地址前缀
     * @return SCIM 组资源
     */
    private ScimGroupResource toResource(GroupResponse group, List<ScimGroupResource.Member> members,
                                         String baseUrl) {
        return new ScimGroupResource(
                List.of(ScimConstants.SCHEMA_GROUP),
                String.valueOf(group.id()),
                group.externalId(),
                group.groupName(),
                members,
                new ScimGroupResource.Meta(ScimConstants.RESOURCE_GROUP,
                        toRfc3339(group.createdTime()), null, baseUrl + "/" + group.id()));
    }

    /**
     * 把操作值转成标量文本。
     *
     * @param value 操作值
     * @return 标量文本;无值返回空串
     */
    private static String scalarOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 去空白,空串按 null 处理。
     *
     * @param value 原值
     * @return 去空白后的值;原值为 null 或空白时返回 null
     */
    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 本地时间转 RFC 3339 UTC 字符串。
     *
     * @param time 数据库读出的本地时间
     * @return RFC 3339 时间串;入参为 null 时返回 null
     */
    private static String toRfc3339(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    /**
     * 把平台业务异常翻译成 SCIM 异常。
     *
     * @param action 业务调用
     * @param <T>    返回类型
     * @return 业务结果
     * @throws ScimException 业务异常按错误码映射为对应的 SCIM 状态
     */
    private static <T> T translate(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND.getCode()) {
                throw ScimException.notFound(e.getMessage());
            }
            if (e.getCode() == ErrorCode.ALREADY_EXISTS.getCode()) {
                throw ScimException.conflict(e.getMessage());
            }
            throw new ScimException(HttpStatus.BAD_REQUEST, ScimConstants.ERROR_INVALID_VALUE, e.getMessage());
        }
    }
}
