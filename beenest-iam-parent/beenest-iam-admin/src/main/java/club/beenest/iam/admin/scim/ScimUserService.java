package club.beenest.iam.admin.scim;

import club.beenest.iam.admin.dto.UserCreateRequest;
import club.beenest.iam.admin.dto.UserOrganizationResponse;
import club.beenest.iam.admin.dto.UserResponse;
import club.beenest.iam.admin.dto.UserUpdateRequest;
import club.beenest.iam.admin.service.UserService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SCIM 用户资源服务:把 SCIM 报文与平台用户模型互转。
 * <p>SCIM 只负责人员数据的增删改查,平台的凭证、角色与待办不在此暴露;
 * 域边界取自当前令牌所属身份域,调用方无法跨域操作。</p>
 */
@Service
public class ScimUserService {

    /** 外部主键在扩展属性中的键名 */
    private static final String ATTR_EXTERNAL_ID = "externalId";
    /** 按外部主键检索时的最大扫描条数 */
    private static final int MAX_SCAN = 1000;
    /** 单页扫描条数 */
    private static final int SCAN_PAGE_SIZE = 100;

    private final UserService userService;

    public ScimUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户。
     * <p>{@code userName} 条件下推到数据库(先模糊查再精确过滤);{@code externalId} 没有独立列,
     * 按最多 {@value #MAX_SCAN} 条扫描匹配。超出扫描范围时返回空结果而不是全量遍历,
     * 避免一次请求把整域用户读进内存。</p>
     *
     * @param filter     过滤表达式,可为 null
     * @param startIndex 起始序号,从 1 开始
     * @param count      本页条数
     * @param baseUrl    资源地址前缀,用于拼 {@code meta.location}
     * @return 列表响应
     * @throws ScimException 过滤属性不支持
     */
    public ScimListResponse<ScimUserResource> list(ScimFilter filter, int startIndex, int count, String baseUrl) {
        int pageSize = Math.max(1, Math.min(count, 100));
        int pageNum = startIndex <= 1 ? 1 : 1 + (startIndex - 1) / pageSize;
        if (filter == null) {
            PageResult<UserResponse> page = userService.page(null, null, pageNum, pageSize);
            return ScimListResponse.of((int) page.getTotal(), startIndex,
                    page.getRecords().stream().map(user -> toResource(user, List.of(), baseUrl)).toList());
        }
        if (filter.matchesAttribute("userName")) {
            PageResult<UserResponse> page = userService.page(filter.value(), null, 1, SCAN_PAGE_SIZE);
            List<UserResponse> matched = page.getRecords().stream()
                    .filter(user -> filter.test(user.username()))
                    .toList();
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().limit(pageSize).map(user -> toResource(user, List.of(), baseUrl)).toList());
        }
        if (filter.matchesAttribute("externalId")) {
            List<UserResponse> matched = scanByExternalId(filter);
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().limit(pageSize).map(user -> toResource(user, List.of(), baseUrl)).toList());
        }
        if (filter.matchesAttribute("id")) {
            UserResponse user = findById(filter.value());
            List<UserResponse> matched = user != null && filter.test(String.valueOf(user.id()))
                    ? List.of(user) : List.of();
            return ScimListResponse.of(matched.size(), startIndex,
                    matched.stream().map(item -> toResource(item, List.of(), baseUrl)).toList());
        }
        throw ScimException.badRequest(ScimConstants.ERROR_INVALID_FILTER,
                "不支持的过滤属性: " + filter.attribute() + "(可用: userName / externalId / id)");
    }

    /**
     * 查询单个用户。
     *
     * @param id      用户主键
     * @param baseUrl 资源地址前缀
     * @return 用户资源
     * @throws ScimException 用户不存在或不属于当前域
     */
    public ScimUserResource get(String id, String baseUrl) {
        UserResponse user = requireUser(id);
        return toResource(user, organizationsOf(user.id()), baseUrl);
    }

    /**
     * 新建用户。
     * <p>{@code userName} 必填且域内唯一;{@code password} 可选,不传表示不创建口令凭证
     * (等待首次登录时走找回口令或由管理员下发)。</p>
     *
     * @param resource 用户资源
     * @param baseUrl  资源地址前缀
     * @return 新建后的用户资源
     * @throws ScimException 缺少登录名、登录名重复或取值非法
     */
    public ScimUserResource create(ScimUserResource resource, String baseUrl) {
        String username = trimToNull(resource.userName());
        if (username == null) {
            throw ScimException.badRequest(ScimConstants.ERROR_INVALID_SYNTAX, "userName 不能为空");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        String externalId = trimToNull(resource.externalId());
        if (externalId != null) {
            attributes.put(ATTR_EXTERNAL_ID, externalId);
        }
        UserCreateRequest request = new UserCreateRequest(
                username,
                displayNameOf(resource),
                emailOf(resource),
                phoneOf(resource),
                null,
                null,
                Boolean.FALSE,
                attributes);
        UserResponse created = translate(() -> userService.create(request));
        // active=false 的账号建号后立即停用:SCIM 的 active 语义是"能否登录",对应平台状态 0
        if (Boolean.FALSE.equals(resource.active())) {
            Long createdId = created.id();
            created = translate(() -> userService.update(createdId, new UserUpdateRequest(
                    null, null, null, 0, null, null, null)));
        }
        return toResource(created, organizationsOf(created.id()), baseUrl);
    }

    /**
     * 整体替换用户属性(PUT)。
     * <p>只更新报文里出现的属性,未出现的保持原值——与 SCIM 的"整体替换"有出入,
     * 但可以避免 HR 系统少传一个字段就把邮箱清空。需要精确清空时用 PATCH 的 remove。</p>
     *
     * @param id       用户主键
     * @param resource 用户资源
     * @param baseUrl  资源地址前缀
     * @return 更新后的用户资源
     * @throws ScimException 用户不存在或取值非法
     */
    public ScimUserResource replace(String id, ScimUserResource resource, String baseUrl) {
        UserResponse existing = requireUser(id);
        UserUpdateRequest request = new UserUpdateRequest(
                displayNameOf(resource),
                emailOf(resource),
                phoneOf(resource),
                resource.active() == null ? null : (Boolean.TRUE.equals(resource.active()) ? 1 : 0),
                null,
                null,
                attributesOf(resource, existing));
        UserResponse updated = translate(() -> userService.update(existing.id(), request));
        return toResource(updated, organizationsOf(updated.id()), baseUrl);
    }

    /**
     * 部分更新用户属性(PATCH)。
     * <p>支持 {@code active}、{@code displayName}/{@code name.formatted}、
     * {@code emails[type eq "work"].value}、{@code phoneNumbers[type eq "mobile"].value}、
     * {@code externalId} 与 {@code password}。{@code remove} 把字符串属性置空,
     * 与平台的"空串即清除"口径一致。</p>
     *
     * @param id      用户主键
     * @param request 部分更新请求
     * @param baseUrl 资源地址前缀
     * @return 更新后的用户资源
     * @throws ScimException 用户不存在、路径不支持或取值非法
     */
    public ScimUserResource patch(String id, ScimPatchRequest request, String baseUrl) {
        UserResponse existing = requireUser(id);
        if (request == null || request.operations() == null || request.operations().isEmpty()) {
            throw ScimException.badRequest(ScimConstants.ERROR_INVALID_SYNTAX, "Operations 不能为空");
        }
        String displayName = null;
        String email = null;
        String phone = null;
        Integer status = null;
        Map<String, String> attributes = new LinkedHashMap<>(existing.attributes() == null
                ? Map.of() : existing.attributes());
        boolean attributesTouched = false;
        for (ScimPatchRequest.Operation operation : request.operations()) {
            String path = normalizePath(operation.path());
            String value = scalarOf(operation.value());
            boolean remove = "remove".equalsIgnoreCase(operation.op());
            switch (path) {
                case "active" -> status = remove ? 0 : (Boolean.parseBoolean(value) ? 1 : 0);
                case "displayname", "name.formatted" -> displayName = remove ? "" : value;
                case "emails", "emails.value", "emails[type eq \"work\"].value" -> email = remove ? "" : value;
                case "phonenumbers", "phonenumbers.value", "phonenumbers[type eq \"mobile\"].value" ->
                        phone = remove ? "" : value;
                case "externalid" -> {
                    if (remove || value == null || value.isBlank()) {
                        attributes.remove(ATTR_EXTERNAL_ID);
                    } else {
                        attributes.put(ATTR_EXTERNAL_ID, value);
                    }
                    attributesTouched = true;
                }
                case "password" -> {
                    // 口令不在 SCIM 的更新路径上处理:重置口令要走待办与口令策略,直接覆盖会绕过二者
                    throw ScimException.badRequest(ScimConstants.ERROR_INVALID_PATH,
                            "不支持通过 PATCH 修改口令,请使用管理端重置口令接口");
                }
                default -> throw ScimException.badRequest(ScimConstants.ERROR_INVALID_PATH,
                        "不支持的属性路径: " + operation.path());
            }
        }
        UserUpdateRequest update = new UserUpdateRequest(displayName, email, phone, status, null, null,
                attributesTouched ? attributes : null);
        UserResponse updated = translate(() -> userService.update(existing.id(), update));
        return toResource(updated, organizationsOf(updated.id()), baseUrl);
    }

    /**
     * 删除用户(逻辑删除)。
     *
     * @param id 用户主键
     * @throws ScimException 用户不存在
     */
    public void delete(String id) {
        UserResponse existing = requireUser(id);
        translate(() -> {
            userService.delete(existing.id());
            return null;
        });
    }

    /**
     * 按外部主键扫描匹配用户。
     *
     * @param filter 过滤表达式
     * @return 命中的用户列表,最多 {@value #MAX_SCAN} 条范围内
     */
    private List<UserResponse> scanByExternalId(ScimFilter filter) {
        List<UserResponse> matched = new ArrayList<>();
        int pageNum = 1;
        int scanned = 0;
        while (scanned < MAX_SCAN) {
            PageResult<UserResponse> page = userService.page(null, null, pageNum, SCAN_PAGE_SIZE);
            for (UserResponse user : page.getRecords()) {
                scanned++;
                String externalId = user.attributes() == null ? null : user.attributes().get(ATTR_EXTERNAL_ID);
                if (filter.test(externalId)) {
                    matched.add(user);
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
     * 按主键查用户。
     *
     * @param id 主键字符串
     * @return 用户;主键非法或不存在时返回 null
     */
    private UserResponse findById(String id) {
        try {
            return userService.get(Long.parseLong(id));
        } catch (NumberFormatException | BusinessException e) {
            return null;
        }
    }

    /**
     * 读取用户,不存在时抛出 SCIM 404。
     *
     * @param id 主键字符串
     * @return 用户
     * @throws ScimException 主键非法或用户不存在
     */
    private UserResponse requireUser(String id) {
        UserResponse user = findById(id);
        if (user == null) {
            throw ScimException.notFound("用户不存在: " + id);
        }
        return user;
    }

    /**
     * 查询用户所属组。
     *
     * @param userId 用户主键
     * @return 组引用列表;查询失败时返回空列表
     */
    private List<ScimUserResource.GroupRef> organizationsOf(Long userId) {
        List<UserOrganizationResponse> organizations = userService.listOrganizations(userId);
        return organizations.stream()
                .map(org -> new ScimUserResource.GroupRef(String.valueOf(org.groupId()), org.groupName()))
                .distinct()
                .toList();
    }

    /**
     * 用户模型转 SCIM 资源。
     *
     * @param user          用户
     * @param groups        所属组
     * @param baseUrl       资源地址前缀
     * @return SCIM 用户资源
     */
    private ScimUserResource toResource(UserResponse user, List<ScimUserResource.GroupRef> groups, String baseUrl) {
        String nickname = user.nickname();
        List<ScimUserResource.Email> emails = user.email() == null || user.email().isBlank()
                ? List.of() : List.of(new ScimUserResource.Email(user.email(), "work", Boolean.TRUE));
        List<ScimUserResource.PhoneNumber> phones = user.phone() == null || user.phone().isBlank()
                ? List.of() : List.of(new ScimUserResource.PhoneNumber(user.phone(), "mobile", Boolean.TRUE));
        String externalId = user.attributes() == null ? null : user.attributes().get(ATTR_EXTERNAL_ID);
        return new ScimUserResource(
                List.of(ScimConstants.SCHEMA_USER),
                String.valueOf(user.id()),
                externalId,
                user.username(),
                nickname == null || nickname.isBlank() ? null : new ScimUserResource.Name(nickname),
                nickname,
                emails,
                phones,
                user.status() != null && user.status() == 1,
                groups.isEmpty() ? null : groups,
                new ScimUserResource.Meta(ScimConstants.RESOURCE_USER,
                        toRfc3339(user.createdTime()), toRfc3339(user.updatedTime()),
                        baseUrl + "/" + user.id()));
    }

    /**
     * 取显示名。
     *
     * @param resource SCIM 资源
     * @return 显示名;报文未给出时返回 null
     */
    private static String displayNameOf(ScimUserResource resource) {
        if (resource.displayName() != null && !resource.displayName().isBlank()) {
            return resource.displayName();
        }
        return resource.name() == null ? null : trimToNull(resource.name().formatted());
    }

    /**
     * 取主邮箱。
     *
     * @param resource SCIM 资源
     * @return 邮箱;报文未给出时返回 null
     */
    private static String emailOf(ScimUserResource resource) {
        if (resource.emails() == null || resource.emails().isEmpty()) {
            return null;
        }
        return resource.emails().stream()
                .filter(item -> item != null && item.value() != null && !item.value().isBlank())
                .map(ScimUserResource.Email::value)
                .findFirst()
                .orElse(null);
    }

    /**
     * 取主手机号。
     *
     * @param resource SCIM 资源
     * @return 手机号;报文未给出时返回 null
     */
    private static String phoneOf(ScimUserResource resource) {
        if (resource.phoneNumbers() == null || resource.phoneNumbers().isEmpty()) {
            return null;
        }
        return resource.phoneNumbers().stream()
                .filter(item -> item != null && item.value() != null && !item.value().isBlank())
                .map(ScimUserResource.PhoneNumber::value)
                .findFirst()
                .orElse(null);
    }

    /**
     * 合并扩展属性:保留原有属性,只覆盖报文给出的外部主键。
     *
     * @param resource SCIM 资源
     * @param existing 库中用户
     * @return 合并后的属性表
     */
    private static Map<String, String> attributesOf(ScimUserResource resource, UserResponse existing) {
        if (resource.externalId() == null) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>(existing.attributes() == null
                ? Map.of() : existing.attributes());
        String externalId = trimToNull(resource.externalId());
        if (externalId == null) {
            attributes.remove(ATTR_EXTERNAL_ID);
        } else {
            attributes.put(ATTR_EXTERNAL_ID, externalId);
        }
        return attributes;
    }

    /**
     * 归一化属性路径:小写、去掉空白。
     *
     * @param path 原始路径
     * @return 归一化路径;为空时返回空串
     */
    private static String normalizePath(String path) {
        return path == null ? "" : path.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 把操作值转成标量文本。
     *
     * @param value 操作值,可能是字符串、布尔、数字或数组
     * @return 标量文本;数组取第一个元素;无值返回空串
     */
    private static String scalarOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "" : scalarOf(list.get(0));
        }
        return String.valueOf(value);
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
