package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.dto.CasSyncStrategyDTO;
import org.apereo.cas.beenest.entity.CasSyncStrategy;
import org.apereo.cas.beenest.mapper.CasSyncStrategyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 同步策略服务
 * <p>
 * 负责同步策略的查询、创建和更新，不让 controller 处理 Map 解析和默认值补齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncStrategyService {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_RETRY_INTERVAL = 60;

    private final CasSyncStrategyMapper syncStrategyMapper;

    public CasSyncStrategy getStrategy(Long serviceId) {
        CasSyncStrategy strategy = syncStrategyMapper.selectByServiceId(serviceId);
        if (strategy == null) {
            strategy = defaultStrategy(serviceId);
        }
        return strategy;
    }

    public void saveStrategy(Long serviceId, CasSyncStrategyDTO dto) {
        CasSyncStrategy existing = syncStrategyMapper.selectByServiceId(serviceId);
        CasSyncStrategy strategy = existing != null ? existing : defaultStrategy(serviceId);

        applyUpdates(strategy, dto);
        if (existing != null) {
            syncStrategyMapper.updateByServiceId(strategy);
        } else {
            syncStrategyMapper.insert(strategy);
        }
    }

    private CasSyncStrategy defaultStrategy(Long serviceId) {
        CasSyncStrategy strategy = new CasSyncStrategy();
        strategy.setServiceId(serviceId);
        strategy.setPushEnabled(false);
        strategy.setPullEnabled(true);
        strategy.setMaxRetries(DEFAULT_MAX_RETRIES);
        strategy.setRetryInterval(DEFAULT_RETRY_INTERVAL);
        return strategy;
    }

    private void applyUpdates(CasSyncStrategy strategy, CasSyncStrategyDTO dto) {
        if (dto.getPushEnabled() != null) {
            strategy.setPushEnabled(dto.getPushEnabled());
        }
        if (dto.getPushUrl() != null) {
            strategy.setPushUrl(dto.getPushUrl());
        }
        if (dto.getPushSecret() != null) {
            strategy.setPushSecret(dto.getPushSecret());
        }
        if (dto.getPushEvents() != null) {
            strategy.setPushEvents(dto.getPushEvents());
        }
        if (dto.getPullEnabled() != null) {
            strategy.setPullEnabled(dto.getPullEnabled());
        }
        if (dto.getMaxRetries() != null) {
            strategy.setMaxRetries(dto.getMaxRetries());
        }
        if (dto.getRetryInterval() != null) {
            strategy.setRetryInterval(dto.getRetryInterval());
        }

        if (strategy.getMaxRetries() == null) {
            strategy.setMaxRetries(DEFAULT_MAX_RETRIES);
        }
        if (strategy.getRetryInterval() == null) {
            strategy.setRetryInterval(DEFAULT_RETRY_INTERVAL);
        }
        if (strategy.getPullEnabled() == null) {
            strategy.setPullEnabled(true);
        }
        if (strategy.getPushEnabled() == null) {
            strategy.setPushEnabled(false);
        }

        validatePushConfiguration(strategy);
    }

    private void validatePushConfiguration(CasSyncStrategy strategy) {
        if (!Boolean.TRUE.equals(strategy.getPushEnabled())) {
            return;
        }
        if (StringUtils.isBlank(strategy.getPushUrl())) {
            throw new BusinessException(400, "启用推送时 pushUrl 不能为空");
        }
        if (StringUtils.isBlank(strategy.getPushSecret())) {
            throw new BusinessException(400, "启用推送时 pushSecret 不能为空");
        }
    }
}
