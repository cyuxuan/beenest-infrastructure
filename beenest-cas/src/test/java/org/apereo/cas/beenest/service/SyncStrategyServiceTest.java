package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.dto.CasSyncStrategyDTO;
import org.apereo.cas.beenest.entity.CasSyncStrategy;
import org.apereo.cas.beenest.mapper.CasSyncStrategyMapper;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncStrategyServiceTest {

    @Test
    void getStrategyReturnsDefaultsWhenMissing() {
        CasSyncStrategyMapper mapper = mock(CasSyncStrategyMapper.class);
        SyncStrategyService service = new SyncStrategyService(mapper);
        when(mapper.selectByServiceId(1001L)).thenReturn(null);

        CasSyncStrategy strategy = service.getStrategy(1001L);

        assertThat(strategy.getServiceId()).isEqualTo(1001L);
        assertThat(strategy.getPushEnabled()).isFalse();
        assertThat(strategy.getPullEnabled()).isTrue();
        assertThat(strategy.getMaxRetries()).isEqualTo(3);
        assertThat(strategy.getRetryInterval()).isEqualTo(60);
    }

    @Test
    void saveStrategyCreatesNewRecordWithDefaults() {
        CasSyncStrategyMapper mapper = mock(CasSyncStrategyMapper.class);
        SyncStrategyService service = new SyncStrategyService(mapper);
        when(mapper.selectByServiceId(1001L)).thenReturn(null);

        CasSyncStrategyDTO dto = new CasSyncStrategyDTO();
        dto.setPushEnabled(true);
        dto.setPushUrl("https://example.com/webhook");
        dto.setPushSecret("secret");

        service.saveStrategy(1001L, dto);

        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat(strategy ->
                strategy.getServiceId().equals(1001L)
                        && Boolean.TRUE.equals(strategy.getPushEnabled())
                        && Boolean.TRUE.equals(strategy.getPullEnabled())
                        && strategy.getMaxRetries().equals(3)
                        && strategy.getRetryInterval().equals(60)));
    }

    @Test
    void saveStrategyRejectsEnabledPushWithoutUrlAndSecret() {
        CasSyncStrategyMapper mapper = mock(CasSyncStrategyMapper.class);
        SyncStrategyService service = new SyncStrategyService(mapper);
        when(mapper.selectByServiceId(1001L)).thenReturn(null);

        CasSyncStrategyDTO dto = new CasSyncStrategyDTO();
        dto.setPushEnabled(true);

        try {
            service.saveStrategy(1001L, dto);
        } catch (BusinessException e) {
            assertThat(e.getMessage()).contains("pushUrl");
            return;
        }
        throw new AssertionError("Expected BusinessException");
    }
}
