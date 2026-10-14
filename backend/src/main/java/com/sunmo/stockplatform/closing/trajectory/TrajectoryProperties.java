/*
 * 장마감 Trajectory(장 후반 시간에 따라 종목 상태가 어떻게 움직였는지 기록한 궤적) 연구 수집의 설정을 closing.trajectory에서 읽어 와요.
 * 기본값은 꺼짐(enabled=false)이고, 켜면 14:30~15:20 사이 5분마다 스냅샷(그 시각의 특징값 묶음)을 남겨요. 추천 점수에는 쓰지 않아요.
 * TrajectoryService, TrajectoryFeatures, MorningOutcomeService, TrajectoryContextCollector가 사용해요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.math.BigDecimal;
import java.time.*;

/**
 * Trajectory 연구 수집의 정책(켜기/끄기, 분석 구간, 스냅샷 간격, 늦은 틱 대기시간, 맥락 자료 최대 나이, 오전 성과 목표/손절 등)을 담는 설정이에요.
 * 관찰 일지의 작성 규칙표처럼 "몇 시부터 몇 분마다, 얼마나 기다렸다가 적을지"를 정하고, 규칙이 말이 안 되면 시작부터 거부해요.
 * 퍼센트 값(pullbackPercent, targetPercent, stopPercent)은 2가 2%를 뜻해요.
 */
@ConfigurationProperties("closing.trajectory")
public record TrajectoryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean indexEnabled,
        @DefaultValue("14:30") LocalTime analysisStart,
        @DefaultValue("15:20") LocalTime analysisEnd,
        @DefaultValue("5") int snapshotMinutes,
        @DefaultValue("2s") Duration watermark,
        @DefaultValue("6m") Duration contextMaxAge,
        @DefaultValue("1") BigDecimal pullbackPercent,
        @DefaultValue("10:30") LocalTime morningEnd,
        @DefaultValue("3") BigDecimal targetPercent,
        @DefaultValue("-2") BigDecimal stopPercent,
        @DefaultValue({"5", "15", "30"}) java.util.List<Integer> volumeComparisonWindows,
        @DefaultValue({"5", "15", "30"}) java.util.List<Integer> turnoverComparisonWindows) {
    /**
     * 하는 일: 비교 구간 목록을 바꿀 수 없는 복사본으로 만들고, 시간·비율 값이 허용 범위인지 검사해요.
     * 받는 것: 설정 파일 값(없으면 @DefaultValue)이에요.
     * 돌려주는 것: 검증된 설정 객체예요. 범위를 벗어나면 IllegalArgumentException을 던져요.
     */
    public TrajectoryProperties {
        volumeComparisonWindows = java.util.List.copyOf(volumeComparisonWindows);
        turnoverComparisonWindows = java.util.List.copyOf(turnoverComparisonWindows);
        // 분석 구간은 09:00~15:20 안이어야 하고, 오전 성과 마감(morningEnd)은 09:00 초과~정오 이하의 5분 단위여야 해요.
        if (snapshotMinutes < 1 || snapshotMinutes > 30 || watermark.isNegative()
                || contextMaxAge.isNegative() || contextMaxAge.isZero()
                || analysisStart.isBefore(LocalTime.of(9, 0)) || !analysisStart.isBefore(analysisEnd)
                || analysisEnd.isAfter(LocalTime.of(15, 20))
                || !morningEnd.isAfter(LocalTime.of(9, 0)) || morningEnd.isAfter(LocalTime.NOON)
                || morningEnd.getMinute() % 5 != 0 || morningEnd.getSecond() != 0
                || pullbackPercent.signum() <= 0 || targetPercent.signum() <= 0
                || stopPercent.signum() >= 0 || stopPercent.compareTo(BigDecimal.valueOf(-100)) <= 0)
            throw new IllegalArgumentException("Invalid closing trajectory policy");
        if (volumeComparisonWindows.isEmpty() || turnoverComparisonWindows.isEmpty()
                || volumeComparisonWindows.stream().anyMatch(n -> n < 1 || n > 60)
                || turnoverComparisonWindows.stream().anyMatch(n -> n < 1 || n > 60))
            throw new IllegalArgumentException("Comparison windows must be between 1 and 60 minutes");
    }
}
