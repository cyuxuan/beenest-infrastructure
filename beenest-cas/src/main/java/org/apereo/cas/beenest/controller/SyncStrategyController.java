package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.CasSyncStrategyDTO;
import org.apereo.cas.beenest.entity.CasSyncStrategy;
import org.apereo.cas.beenest.service.SyncStrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 同步策略管理控制器
 * <p>
 * 配置每个应用的用户数据同步策略：
 * - 推送模式（Webhook URL、订阅事件、签名密钥）
 * - 拉取模式（开启/关闭）
 * - 重试策略（次数、间隔）
 */
@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class SyncStrategyController {

    private final SyncStrategyService syncStrategyService;

    /**
     * 获取应用的同步策略
     */
    @GetMapping("/{serviceId}")
    public R<CasSyncStrategy> getStrategy(@PathVariable Long serviceId) {
        return R.ok(syncStrategyService.getStrategy(serviceId));
    }

    /**
     * 配置应用的同步策略
     *
     * @param serviceId 应用 ID
     * @param request   策略配置
     *                   - pushEnabled: 是否启用推送
     *                   - pushUrl: Webhook URL
     *                   - pushEvents: 订阅事件（逗号分隔：CREATE,UPDATE,DELETE,STATUS_CHANGE）
     *                   - pullEnabled: 是否启用拉取
     *                   - maxRetries: 最大重试次数
     *                   - retryInterval: 重试间隔（秒）
     */
    @PostMapping("/{serviceId}")
    public R<Void> configureStrategy(@PathVariable Long serviceId,
                                      @RequestBody CasSyncStrategyDTO request) {
        syncStrategyService.saveStrategy(serviceId, request);

        LOGGER.info("更新同步策略: serviceId={}", serviceId);
        return R.ok();
    }
}
