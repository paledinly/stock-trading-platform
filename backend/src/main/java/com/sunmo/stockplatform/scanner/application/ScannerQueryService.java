/*
 * 스캐너 설정의 생성·수정·삭제와 감지 기록 조회를 처리하는 서비스 파일이에요.
 * 수정할 때 version을 비교해 동시에 고친 경우를 막아요.
 * ScannerController가 사용해요.
 */
package com.sunmo.stockplatform.scanner.application;

import com.sunmo.stockplatform.common.error.*;
import com.sunmo.stockplatform.scanner.api.ScannerDtos.*;
import com.sunmo.stockplatform.scanner.domain.*;
import com.sunmo.stockplatform.scanner.infrastructure.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/**
 * 스캐너 설정 관리와 감지 기록 조회를 맡는 클래스예요.
 * 도서관 사서처럼 설정 카드를 새로 만들거나 고치고, 감지 기록 대장을 최신순으로 찾아 보여 줘요.
 * 클래스 전체가 트랜잭션(DB 작업을 한 묶음으로 처리하는 단위) 안에서 돌아가요.
 */
@Service
@Transactional
public class ScannerQueryService {
    // 단일 사용자 구조라 소유자 id를 1로 고정해요.
    private static final long OWNER = 1L;
    // '오늘'을 판단할 때 한국 시간 기준을 써요.
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerSettingRepository settings;
    private final ScannerDetectionRepository detections;

    /**
     * 하는 일: 설정·감지 레포지토리(DB 조회 담당)를 받아 보관해요.
     * 받는 것: settings, detections.
     * 돌려주는 것: 없음(생성자).
     */
    public ScannerQueryService(ScannerSettingRepository settings, ScannerDetectionRepository detections) {
        this.settings = settings;
        this.detections = detections;
    }

    /**
     * 하는 일: 내 설정 전체를 id 순으로 가져와요.
     * 받는 것: 없음.
     * 돌려주는 것: 설정 응답 목록.
     */
    @Transactional(readOnly = true)
    public List<SettingResponse> settings() {
        return settings.findByOwnerIdOrderById(OWNER).stream().map(SettingResponse::from).toList();
    }

    /**
     * 하는 일: 요청 내용으로 새 설정을 저장해요. 이름 앞뒤 공백은 지워요.
     * 받는 것: r(설정 요청).
     * 돌려주는 것: 저장된 설정.
     */
    public SettingResponse create(SettingRequest r) {
        return SettingResponse.from(settings.save(new ScannerSetting(OWNER, r.name().trim(), r.type(),
                r.minChangeRate(), r.minVolumeRatio(), r.minFiveMinuteTradingValue(), r.minDailyTradingValue(),
                r.minPrice(), r.includeEtf(), r.cooldownSeconds())));
    }

    /**
     * 하는 일: 설정을 고쳐요. 종류(type)는 바꾸지 않아요.
     * 받는 것: id, r(설정 요청, version 포함).
     * 돌려주는 것: 고친 설정. version이 다르면 다른 곳에서 먼저 고친 것이라 409 충돌 오류를 던져요.
     */
    public SettingResponse update(long id, SettingRequest r) {
        ScannerSetting s = require(id);
        if (s.getVersion() != r.version())
            throw error(HttpStatus.CONFLICT, "Scanner setting changed; refresh and retry");
        s.update(r.name().trim(), r.minChangeRate(), r.minVolumeRatio(), r.minFiveMinuteTradingValue(),
                r.minDailyTradingValue(), r.minPrice(), r.includeEtf(), r.cooldownSeconds(), r.active());
        return SettingResponse.from(s);
    }

    /**
     * 하는 일: 설정을 지워요.
     * 받는 것: id.
     * 돌려주는 것: 없음.
     * ⚠️ 확인 필요: scanner_detection이 이 설정을 ON DELETE 없이 참조해서, 감지 기록이 있는 설정은 DB 제약 오류로 지워지지 않을 수 있어요.
     */
    public void delete(long id) {
        settings.delete(require(id));
    }

    /**
     * 하는 일: 오늘 감지만 조회하는 짧은 버전이에요.
     * 받는 것: type, limit.
     * 돌려주는 것: 감지 응답 목록.
     */
    @Transactional(readOnly = true)
    public List<DetectionResponse> detections(ScannerType type, int limit) {
        return detections(type, limit, true);
    }

    /**
     * 하는 일: 감지 기록을 최신순으로 조회해요.
     * 받는 것: type(null이면 전체), limit(1~100으로 보정), todayOnly(한국 시간 오늘 것만 볼지).
     * 돌려주는 것: 감지 응답 목록.
     */
    @Transactional(readOnly = true)
    public List<DetectionResponse> detections(ScannerType type, int limit, boolean todayOnly) {
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        var list = todayOnly ? todayDetections(type, page)
                : type == null ? detections.findAllByOrderByDetectedAtDesc(page)
                        : detections.findByTypeOrderByDetectedAtDesc(type, page);
        return list.stream().map(DetectionResponse::from).toList();
    }

    /**
     * 하는 일: 한국 시간 오늘 0시 이후 감지만 가져와요.
     * 받는 것: type(null이면 전체), page(개수 제한).
     * 돌려주는 것: 감지 엔티티 목록.
     */
    private List<ScannerDetection> todayDetections(ScannerType type, PageRequest page) {
        Instant marketDayStart = LocalDate.now(MARKET_ZONE).atStartOfDay(MARKET_ZONE).toInstant();
        return type == null ? detections.findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(marketDayStart, page)
                : detections.findByTypeAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(type, marketDayStart, page);
    }

    /**
     * 하는 일: 감지 기록 한 건을 찾아요.
     * 받는 것: id.
     * 돌려주는 것: 감지 응답. 없으면 404 오류를 던져요.
     */
    @Transactional(readOnly = true)
    public DetectionResponse detection(long id) {
        return detections.findById(id).map(DetectionResponse::from)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Detection not found: " + id));
    }

    /**
     * 하는 일: 내 설정을 id로 찾아요.
     * 받는 것: id.
     * 돌려주는 것: 설정 엔티티. 없으면 404 오류를 던져요.
     */
    private ScannerSetting require(long id) {
        return settings.findByIdAndOwnerId(id, OWNER)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Scanner setting not found: " + id));
    }

    /**
     * 하는 일: API 오류 객체를 만들어요. 오류 코드는 항상 INVALID_REQUEST예요.
     * 받는 것: s(HTTP 상태), m(메시지).
     * 돌려주는 것: ApplicationException.
     */
    private ApplicationException error(HttpStatus s, String m) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, s, m);
    }
}
