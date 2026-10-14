/*
 * ScannerEngine의 재알림 대기(cooldown) 처리를 확인해요. 보통 Redis(공유 메모리 저장소)에 대기 키를 두지만,
 * Redis가 죽거나 없을 때도 검출을 잃지 않고 로컬 대기로 대신하며 그 사실이 진단에 보여야 해요.
 */
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

/** 스캐너 재알림 대기의 Redis 대체 동작을 점검하는 테스트예요. */
class ScannerEngineCooldownTest {
    private final RealtimeDiagnostics diagnostics = new RealtimeDiagnostics();

    /**
     * Redis 장애 시 로컬 대기로 대신하고, 그 사실이 진단에 남는지 확인해요.
     * 준비: 목(mock, 가짜 객체) Redis가 항상 연결 실패 예외를 던지게 해요.
     * 기대: 첫 검출은 통과, 같은 종목 재검출은 막히고, 다른 종목은 통과하며 대체 3회·오류 기록 1건(분당 1건)이 남아요.
     */
    @Test
    void redisFailureFallsBackToLocalCooldownAndIsVisible() {
        // 준비
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        ScannerEngine engine = engine(redis);
        ScannerSetting setting = setting();

        // 실행 및 확인
        assertThat(engine.acquire(setting, "005930", UUID.randomUUID())).as("first detection is kept").isTrue();
        assertThat(engine.acquire(setting, "005930", UUID.randomUUID())).as("cooldown still applies").isFalse();
        assertThat(engine.acquire(setting, "000660", UUID.randomUUID())).isTrue();

        RealtimeDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertThat(snapshot.scannerCooldownFallbacks()).isEqualTo(3);
        assertThat(snapshot.lastScannerCooldownFallbackAt()).isNotNull();
        assertThat(snapshot.recentErrors()).as("one error entry per minute").hasSize(1)
                .first().asString().contains("RedisConnectionFailureException");
    }

    /**
     * Redis가 아예 없어도 검출을 유지하고, Redis가 있으면 Redis 결과를 따르는지 확인해요.
     * 준비: Redis 없이 한 번 acquire하고, 이후 setIfAbsent가 false(이미 대기 중)인 Redis로 다시 acquire해요.
     * 기대: 첫 번째는 통과(대체 1회), 두 번째는 Redis 판단대로 막히고 대체 횟수는 그대로예요.
     */
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

    /** 대기 처리에 필요한 의존성만 넣고 나머지는 null로 둔 스캐너 엔진을 만드는 도우미예요. */
    private ScannerEngine engine(StringRedisTemplate redis) {
        @SuppressWarnings("unchecked") ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new ScannerEngine(null, null, null, null, null, null, null, provider, null, diagnostics, null,
                new ObjectMapper(), null);
    }

    /** 대기 300초인 VOLUME 스캐너 설정 목을 만드는 도우미예요. */
    private ScannerSetting setting() {
        ScannerSetting setting = mock(ScannerSetting.class);
        when(setting.getId()).thenReturn(1L);
        when(setting.getType()).thenReturn(ScannerType.VOLUME);
        when(setting.getCooldownSeconds()).thenReturn(300);
        return setting;
    }
}
