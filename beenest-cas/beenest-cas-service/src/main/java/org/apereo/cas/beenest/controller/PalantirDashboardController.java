package org.apereo.cas.beenest.controller;

import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.web.AbstractController;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.web.cookie.CookieValueManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.util.FileCopyUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 同应用内的 Palantir 管理控制台。
 * <p>
 * 这里不再依赖 CAS 自带的 Palantir 安全链，而是直接复用当前 CAS 实例的登录态，
 * 让管理页面与 CAS 主站点留在同一个进程和同一个部署单元里。
 */
@Controller
@RequestMapping("/palantir")
@RequiredArgsConstructor
@Tag(name = "Palantir")
public class PalantirDashboardController extends AbstractController {
    private static final String SECTION_OVERVIEW = "overview";
    private static final String SECTION_SERVICES = "services";
    private static final String SECTION_ACCESS = "access";
    private static final String SECTION_SESSIONS = "sessions";

    private final CasConfigurationProperties casProperties;
    private final EndpointLinksResolver endpointLinksResolver;
    private final WebEndpointProperties webEndpointProperties;
    private final TicketRegistry ticketRegistry;
    private final CookieValueManager cookieValueManager;

    /**
     * 管理控制台首页。
     *
     * @param authentication 当前认证信息
     * @param serviceKey 当前选择的服务标识
     * @param request HTTP 请求
     * @return 视图模型
     * @throws Exception 读取模板或服务定义失败时抛出
     */
    @GetMapping(path = {StringUtils.EMPTY, "/dashboard", "/", "/dashboard/**"}, produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Dashboard home page", description = "Dashboard home page")
    public ModelAndView dashboardRoot(final Authentication authentication,
                                      @RequestParam(name = "serviceKey", required = false) final String serviceKey,
                                      @RequestParam(name = "section", required = false) final String section,
                                      final HttpServletRequest request) throws Exception {
        val resolvedAuthentication = resolveAuthentication(authentication, request);
        return buildModelAndView(resolvedAuthentication, serviceKey, normalizeSection(section), request);
    }

    private String buildLoginRedirectUrl() {
        val targetService = casProperties.getServer().getPrefix() + "/palantir";
        return casProperties.getServer().getPrefix() + "/login?service=" + URLEncoder.encode(targetService, StandardCharsets.UTF_8);
    }

    private ModelAndView buildModelAndView(final Object authentication,
                                           final String serviceKey,
                                           final String section,
                                           final HttpServletRequest request) throws Exception {
        val mav = new ModelAndView("palantir/casPalantirDashboardView");
        mav.addObject("authentication", authentication);
        mav.addObject("casServerPrefix", casProperties.getServer().getPrefix());
        mav.addObject("httpRequestSecure", request.isSecure());
        mav.addObject("httpRequestMethod", request.getMethod());
        val basePath = webEndpointProperties.getBasePath();
        val endpoints = endpointLinksResolver.resolveLinks(basePath);
        val actuatorEndpoints = endpoints
            .entrySet()
            .stream()
            .map(entry -> Pair.of(entry.getKey(), casProperties.getServer().getPrefix() + entry.getValue().getHref()))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
        mav.addObject("actuatorEndpoints", actuatorEndpoints);
        val serviceDefinitions = loadServiceDefinitions();
        mav.addObject("serviceDefinitions", serviceDefinitions);
        mav.addObject("selectedServiceKey", serviceKey);
        mav.addObject("selectedService", resolveSelectedService(serviceDefinitions, serviceKey));
        mav.addObject("selectedSection", section);
        mav.addObject("loginRedirectUrl", buildLoginRedirectUrl());
        mav.addObject("managementMenuTitle", buildSectionTitle(section));
        mav.addObject("managementMenuItems", buildManagementMenuItems(serviceKey, section));
        mav.addObject("managementReadOnly", authentication == null);
        mav.addObject("sectionOverviewActive", SECTION_OVERVIEW.equals(section));
        mav.addObject("sectionServicesActive", SECTION_SERVICES.equals(section));
        mav.addObject("sectionAccessActive", SECTION_ACCESS.equals(section));
        mav.addObject("sectionSessionsActive", SECTION_SESSIONS.equals(section));
        mav.addObject("currentTicketGrantingTicketId", resolveCurrentTicketGrantingTicketId(request));
        return mav;
    }

    /**
     * 解析当前请求的认证信息。
     * <p>
     * 优先使用 Spring Security 上下文；如果当前请求尚未绑定认证对象，
     * 则从 TGC Cookie 中直接恢复 Ticket-Granting Ticket，对同应用管理页做兜底桥接。
     *
     * @param authentication Spring Security 认证对象
     * @param request 当前请求
     * @return 可用认证对象，若无有效登录态则返回 null
     */
    private Object resolveAuthentication(final Authentication authentication,
                                         final HttpServletRequest request) {
        if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
            return authentication;
        }
        val ticketGrantingTicket = resolveTicketGrantingTicket(request);
        return ticketGrantingTicket != null ? ticketGrantingTicket.getAuthentication() : null;
    }

    /**
     * 从请求 Cookie 中恢复 TGT。
     *
     * @param request 当前请求
     * @return TicketGrantingTicket，未找到或已失效时返回 null
     */
    private TicketGrantingTicket resolveTicketGrantingTicket(final HttpServletRequest request) {
        try {
            if (request.getCookies() == null) {
                return null;
            }
            for (val cookie : request.getCookies()) {
                if (!"TGC".equals(cookie.getName())) {
                    continue;
                }
                val cookieValue = cookieValueManager.obtainCookieValue(cookie, request);
                if (StringUtils.isBlank(cookieValue)) {
                    continue;
                }
                val ticket = ticketRegistry.getTicket(cookieValue);
                if (ticket instanceof TicketGrantingTicket tgt) {
                    return tgt;
                }
            }
        } catch (final Exception ignored) {
            return null;
        }
        return null;
    }

    private List<Map<String, Object>> buildManagementMenuItems(final String selectedServiceKey, final String section) {
        val prefix = casProperties.getServer().getPrefix();
        val items = new ArrayList<Map<String, Object>>();

        items.add(menuItem("管理首页", prefix + "/palantir?section=" + SECTION_OVERVIEW, "查看当前登录态与管理入口", SECTION_OVERVIEW.equals(section)));
        items.add(menuItem("服务注册", prefix + "/palantir?section=" + SECTION_SERVICES, "查看、导入和维护已注册服务", SECTION_SERVICES.equals(section)));
        items.add(menuItem("准入校验", prefix + "/palantir?section=" + SECTION_ACCESS, "查看服务准入信息与访问控制说明", SECTION_ACCESS.equals(section)));
        items.add(menuItem("会话管理", prefix + "/palantir?section=" + SECTION_SESSIONS, "查看当前登录会话与 TGT 状态", SECTION_SESSIONS.equals(section)));
        items.add(menuItem("主登录页", prefix + "/login", "返回 CAS 主登录入口", false));
        return items;
    }

    private String buildSectionTitle(final String section) {
        return switch (section) {
            case SECTION_SERVICES -> "服务注册";
            case SECTION_ACCESS -> "准入校验";
            case SECTION_SESSIONS -> "会话管理";
            default -> "CAS 同应用管理";
        };
    }

    private String normalizeSection(final String section) {
        if (SECTION_SERVICES.equals(section) || SECTION_ACCESS.equals(section) || SECTION_SESSIONS.equals(section)) {
            return section;
        }
        return SECTION_OVERVIEW;
    }

    private String resolveCurrentTicketGrantingTicketId(final HttpServletRequest request) {
        val ticketGrantingTicket = resolveTicketGrantingTicket(request);
        return ticketGrantingTicket != null ? ticketGrantingTicket.getId() : null;
    }

    private Map<String, Object> menuItem(final String label, final String href, final String description, final boolean active) {
        val item = new LinkedHashMap<String, Object>();
        item.put("label", label);
        item.put("href", href);
        item.put("description", description);
        item.put("active", active);
        return item;
    }

    private List<Map<String, Object>> loadServiceDefinitions() throws IOException {
        val jsonFiles = new ArrayList<Map<String, Object>>();
        val resolver = new PathMatchingResourcePatternResolver();
        val resources = resolver.getResources("classpath:services/**/*.json");
        val objectMapper = new ObjectMapper();

        for (val resource : resources) {
            try (val input = resource.getInputStream()) {
                val contents = new String(FileCopyUtils.copyToByteArray(input), StandardCharsets.UTF_8);
                val filename = resource.getFilename() != null ? resource.getFilename() : "service";
                val key = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
                val metadata = objectMapper.readValue(contents, new TypeReference<Map<String, Object>>() { });
                val item = new LinkedHashMap<String, Object>();
                item.put("fileKey", key);
                item.put("id", metadata.get("id"));
                item.put("name", Optional.ofNullable(metadata.get("name")).orElse(key));
                item.put("serviceId", metadata.get("serviceId"));
                item.put("description", metadata.get("description"));
                item.put("rawJson", contents);
                item.put("managementHref", casProperties.getServer().getPrefix() + "/palantir?section=" + SECTION_SERVICES + "&serviceKey=" + key);
                jsonFiles.add(item);
            }
        }
        jsonFiles.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return jsonFiles;
    }

    private Map<String, Object> resolveSelectedService(final List<Map<String, Object>> serviceDefinitions,
                                                       final String serviceKey) {
        if (StringUtils.isBlank(serviceKey)) {
            return serviceDefinitions.isEmpty() ? Map.of() : serviceDefinitions.getFirst();
        }
        return serviceDefinitions.stream()
            .filter(item -> serviceKey.equals(item.get("fileKey")))
            .findFirst()
            .orElseGet(() -> serviceDefinitions.isEmpty() ? Map.of() : serviceDefinitions.getFirst());
    }
}
