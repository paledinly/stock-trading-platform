package com.sunmo.stockplatform.scanner.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunmo.stockplatform.market.application.RealtimeDiagnostics;
import com.sunmo.stockplatform.scanner.domain.ScannerSetting;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScannerEngineCooldownTest {
    private final RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();

    @Test
    void redisFailureFallsBackToLocalCooldownAndIsVisible() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        ScannerEngine engine = engine(redis);
        ScannerSetting setting = setting();

        assertThat(engine.acquire(setting, "005930", UUID.randomUUID())).as("first detection is kept").isTrue();
        assertThat(engine.acquire(setting, "005930", UUID.randomUUID())).as("cooldown still applies").isFalse();
        assertThat(engine.acquire(setting, "000660", UUID.randomUUID())).isTrue();

        RealtimeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertThat(snapshot.scannerCooldownFallbacks()).isEqualTo(3);
        assertThat(snapshot.lastScannerCooldownFallbackAt()).isNotNull();
        assertThat(snapshot.recentErrors()).as("one error entry per minute").hasSize(1)
                .first().asString().contains("RedisConnectionFailureException");
    }

    @Test
    void missingRedisAlsoKeepsDetectionsAndRedisResultWinsWhenAvailable() {
        ScannerSetting setting = setting();
        assertThat(engine(null).acquire(setting, "005930", UUID.randomUUID())).isTrue();
        assertThat(diagnostics.snapshot().scannerCooldownFallbacks()).isEqualTo(1);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(engine(redis).acquire(setting, "005930", UUID.randomUUID())).isFalse();
        assertThat(diagnostics.snapshot().scannerCooldownFallbacks()).isEqualTo(1);
    }

    private ScannerEngine engine(StringRedisTemplate redis) {
        @SuppressWarnings("unchecked") ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new ScannerEngine(null, null, null, null, null, null, null, provider, null, diagnostics, null,
                new ObjectMapper(), null);
    }

    private ScannerSetting setting() {
        ScannerSetting setting = mock(ScannerSetting.class);
        when(setting.getId()).thenReturn(1L);
        when(setting.getType()).thenReturn(ScannerType.VOLUME);
        when(setting.getCooldownSeconds()).thenReturn(300);
        return setting;
    }
}
