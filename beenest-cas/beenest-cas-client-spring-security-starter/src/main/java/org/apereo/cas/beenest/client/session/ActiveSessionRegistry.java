package org.apereo.cas.beenest.client.session;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃 Session 注册表
 * <p>
 * 通过 {@link HttpSessionListener} 追踪所有活跃 HTTP Session，
 * 供 SLO 单点登出和用户同步时按 sessionId/userId 查找目标 session。
 * <p>
 * 为什么需要这个？
 * CAS Server 的 SLO POST 来自后端 HTTP 调用，不携带用户的 JSESSIONID Cookie，
 * 因此 {@code httpRequest.getSession(false)} 只能拿到空 session。
 * 通过 Listener 在 session 创建/销毁时维护全局映射，才能实现真正的跨 session 销毁。
 * <p>
 * 包含 userId → sessionId 反向索引，优化按用户查找 session 的性能（O(1) 查找，非 O(n) 遍历）。
 */
@Slf4j
public class ActiveSessionRegistry implements HttpSessionListener {

    /** sessionId → HttpSession 映射 */
    private final ConcurrentHashMap<String, HttpSession> sessions = new ConcurrentHashMap<>();

    /** ST → SessionId 映射（用于 session 超时时清理孤儿 ST） */
    private final ConcurrentHashMap<String, String> stToSessionId = new ConcurrentHashMap<>();

    /** SessionId → ST 反向映射（用于 session 销毁时清理，避免依赖已失效的 session 属性） */
    private final ConcurrentHashMap<String, String> sessionIdToSt = new ConcurrentHashMap<>();

    /** SessionId → userId 反向映射（用于 SLO 到达时，在 session 失效后仍能清理用户级缓存） */
    private final ConcurrentHashMap<String, String> sessionIdToUserId = new ConcurrentHashMap<>();

    /** userId → Set<sessionId> 反向索引（用于按 userId 快速查找 sessions，避免 O(n) 遍历） */
    private final ConcurrentHashMap<String, Set<String>> userIdToSessionIds = new ConcurrentHashMap<>();

    /**
     * Session 创建时注册
     */
    @Override
    public void sessionCreated(HttpSessionEvent se) {
        // Session 在 CAS 认证成功后通过 register() 显式注册
        LOGGER.debug("Session 创建: id={}", se.getSession().getId());
    }

    /**
     * Session 销毁时移除，并清理关联的 ST 映射和 userId 索引，防止内存泄漏
     */
    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        String sessionId = se.getSession().getId();
        sessions.remove(sessionId);

        // 通过反向映射清理 ST
        String st = sessionIdToSt.remove(sessionId);
        if (st != null) {
            stToSessionId.remove(st);
        }

        sessionIdToUserId.remove(sessionId);

        // 清理 userId 索引中包含此 sessionId 的条目
        userIdToSessionIds.values().forEach(set -> set.remove(sessionId));

        LOGGER.debug("Session 移除: id={}", sessionId);
    }

    /**
     * 注册 ST → sessionId 反向映射（ST 校验成功后调用）
     */
    public void registerStMapping(String serviceTicket, String sessionId) {
        stToSessionId.put(serviceTicket, sessionId);
        sessionIdToSt.put(sessionId, serviceTicket);
    }

    /**
     * 按 ST 查找并移除 sessionId
     */
    public String removeSessionIdBySt(String serviceTicket) {
        String sessionId = stToSessionId.remove(serviceTicket);
        if (sessionId != null) {
            sessionIdToSt.remove(sessionId);
        }
        return sessionId;
    }

    /**
     * 主动注册 session（用于 ST 校验成功后立即注册，不等 Listener 回调）
     */
    public void register(HttpSession session) {
        if (session != null) {
            sessions.put(session.getId(), session);
        }
    }

    /**
     * 注册 CAS 登录成功后的会话快照
     * <p>
     * 这个方法负责把“session 实体 / ST / userId / 会话属性”一次性写入注册表，
     * 让后续的 SLO、用户同步和本地会话刷新都能沿着同一条索引链路工作。
     *
     * @param session       HTTP Session
     * @param userSession   CAS 用户会话
     * @param serviceTicket CAS 服务票据（可为空）
     */
    public void registerAuthenticatedSession(HttpSession session, CasUserSession userSession, String serviceTicket) {
        if (session == null || userSession == null) {
            return;
        }

        String sessionId = session.getId();
        String userId = userSession.getUserId();
        String resolvedTicket = StringUtils.hasText(serviceTicket) ? serviceTicket : userSession.getServiceTicket();
        if (StringUtils.hasText(resolvedTicket)) {
            userSession.setServiceTicket(resolvedTicket);
        }

        session.setAttribute(CasUserSession.SESSION_KEY, userSession);
        register(session);

        if (StringUtils.hasText(userId)) {
            registerUserSession(userId, sessionId);
        }
        if (StringUtils.hasText(resolvedTicket)) {
            registerStMapping(resolvedTicket, sessionId);
        }

        LOGGER.debug("CAS 登录会话已注册: userId={}, sessionId={}, st={}", userId, sessionId, resolvedTicket);
    }

    /**
     * 注册 userId 与 sessionId 的关联（认证成功后调用）
     * <p>
     * 用于后续按 userId 快速查找该用户的所有 session，支持用户数据变更时批量刷新。
     *
     * @param userId    用户 ID
     * @param sessionId HTTP Session ID
     */
    public void registerUserSession(String userId, String sessionId) {
        if (userId != null && sessionId != null) {
            sessionIdToUserId.put(sessionId, userId);
            userIdToSessionIds.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
    }

    /**
     * 按 sessionId 获取 session
     */
    public HttpSession getSession(String sessionId) {
        return sessionId != null ? sessions.get(sessionId) : null;
    }

    /**
     * 按 sessionId 获取并移除 session
     */
    public HttpSession removeSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        // 清理 userId 索引
        userIdToSessionIds.values().forEach(set -> set.remove(sessionId));
        sessionIdToUserId.remove(sessionId);
        String st = sessionIdToSt.remove(sessionId);
        if (st != null) {
            stToSessionId.remove(st);
        }
        return sessions.remove(sessionId);
    }

    /**
     * 按 userId 查找所有关联的活跃 session
     * <p>
     * 使用 userId 反向索引实现 O(1) 查找，替代原先的 O(n) 全量遍历。
     *
     * @param userId 用户 ID
     * @return 该用户的所有活跃 session 列表
     */
    public List<HttpSession> getSessionsByUserId(String userId) {
        Set<String> sessionIds = userIdToSessionIds.get(userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return sessionIds.stream()
                .map(sessions::get)
                .filter(session -> {
                    if (session == null) return false;
                    try {
                        return session.getAttribute(CasUserSession.SESSION_KEY) != null;
                    } catch (IllegalStateException e) {
                        // session 已 invalidated，跳过
                        return false;
                    }
                })
                .toList();
    }

    /**
     * 通过 sessionId 反查 userId。
     *
     * @param sessionId HTTP Session ID
     * @return userId，若不存在则返回 null
     */
    public String getUserIdBySessionId(String sessionId) {
        return sessionId != null ? sessionIdToUserId.get(sessionId) : null;
    }

    /**
     * 获取当前活跃 session 数量
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
