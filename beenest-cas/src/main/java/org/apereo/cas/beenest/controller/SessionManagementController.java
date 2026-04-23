package org.apereo.cas.beenest.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理控制器（踢人下线）。
 * <p>
 * CAS 原生通过 TGT 销毁 + SLO 级联实现会话管理。
 * 此控制器提供管理 API，用于查询和销毁用户会话。
 * <p>
 * 销毁 TGT 时 CAS LogoutManager 自动向所有注册服务发送 SLO 请求。
 * 已配置 track-descendant-tickets: true 确保级联清理。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/session")
@RequiredArgsConstructor
public class SessionManagementController {

    private final TicketRegistry ticketRegistry;

    /**
     * 踢指定用户的所有会话
     *
     * @param userId 用户 ID
     * @return 被销毁的 TGT 数量
     */
    @DeleteMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> kickAllSessions(@PathVariable String userId) {
        List<String> destroyed = new ArrayList<>();
        ticketRegistry.getTickets()
            .stream()
            .filter(t -> t instanceof TicketGrantingTicket)
            .map(t -> (TicketGrantingTicket) t)
            .filter(tgt -> {
                var principal = tgt.getAuthentication().getPrincipal();
                return userId.equals(principal.getId());
            })
            .forEach(tgt -> {
                try {
                    ticketRegistry.deleteTicket(tgt.getId());
                    destroyed.add(tgt.getId());
                    LOGGER.info("踢人下线: userId={}, tgtId={}", userId, tgt.getId());
                } catch (Exception e) {
                    LOGGER.warn("销毁 TGT 失败: tgtId={}, error={}", tgt.getId(), e.getMessage());
                }
            });

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("destroyedCount", destroyed.size());
        result.put("destroyedTickets", destroyed);
        return ResponseEntity.ok(result);
    }

    /**
     * 踢指定会话（销毁单个 TGT）
     *
     * @param tgtId 票据授予票据 ID
     * @return 操作结果
     */
    @DeleteMapping("/ticket/{tgtId}")
    public ResponseEntity<Map<String, Object>> kickSession(@PathVariable String tgtId) {
        Map<String, Object> result = new HashMap<>();
        try {
            var ticket = ticketRegistry.getTicket(tgtId);
            if (ticket instanceof TicketGrantingTicket tgt) {
                ticketRegistry.deleteTicket(tgtId);
                result.put("destroyed", true);
                result.put("tgtId", tgtId);
                result.put("userId", tgt.getAuthentication().getPrincipal().getId());
                LOGGER.info("销毁 TGT: tgtId={}, userId={}", tgtId, tgt.getAuthentication().getPrincipal().getId());
            } else {
                result.put("destroyed", false);
                result.put("error", "Ticket not found or not a TGT");
            }
        } catch (Exception e) {
            result.put("destroyed", false);
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }
}
