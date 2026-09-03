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

@Service
@Transactional
public class ScannerQueryService {
    private static final long OWNER = 1L;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final ScannerSettingRepository settings;
    private final ScannerDetectionRepository detections;

    public ScannerQueryService(ScannerSettingRepository settings, ScannerDetectionRepository detections) {
        this.settings = settings;
        this.detections = detections;
    }

    @Transactional(readOnly = true)
    public List<SettingResponse> settings() {
        return settings.findByOwnerIdOrderById(OWNER).stream().map(SettingResponse::from).toList();
    }

    public SettingResponse create(SettingRequest r) {
        return SettingResponse.from(settings.save(new ScannerSetting(OWNER, r.name().trim(), r.type(),
                r.minChangeRate(), r.minVolumeRatio(), r.minFiveMinuteTradingValue(), r.minDailyTradingValue(),
                r.minPrice(), r.includeEtf(), r.cooldownSeconds())));
    }

    public SettingResponse update(long id, SettingRequest r) {
        ScannerSetting s = require(id);
        if (s.getVersion() != r.version())
            throw error(HttpStatus.CONFLICT, "Scanner setting changed; refresh and retry");
        s.update(r.name().trim(), r.minChangeRate(), r.minVolumeRatio(), r.minFiveMinuteTradingValue(),
                r.minDailyTradingValue(), r.minPrice(), r.includeEtf(), r.cooldownSeconds(), r.active());
        return SettingResponse.from(s);
    }

    public void delete(long id) {
        settings.delete(require(id));
    }

    @Transactional(readOnly = true)
    public List<DetectionResponse> detections(ScannerType type, int limit) {
        return detections(type, limit, true);
    }

    @Transactional(readOnly = true)
    public List<DetectionResponse> detections(ScannerType type, int limit, boolean todayOnly) {
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        var list = todayOnly ? todayDetections(type, page)
                : type == null ? detections.findAllByOrderByDetectedAtDesc(page)
                        : detections.findByTypeOrderByDetectedAtDesc(type, page);
        return list.stream().map(DetectionResponse::from).toList();
    }

    private List<ScannerDetection> todayDetections(ScannerType type, PageRequest page) {
        Instant marketDayStart = LocalDate.now(MARKET_ZONE).atStartOfDay(MARKET_ZONE).toInstant();
        return type == null ? detections.findByDetectedAtGreaterThanEqualOrderByDetectedAtDesc(marketDayStart, page)
                : detections.findByTypeAndDetectedAtGreaterThanEqualOrderByDetectedAtDesc(type, marketDayStart, page);
    }

    @Transactional(readOnly = true)
    public DetectionResponse detection(long id) {
        return detections.findById(id).map(DetectionResponse::from)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Detection not found: " + id));
    }

    private ScannerSetting require(long id) {
        return settings.findByIdAndOwnerId(id, OWNER)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Scanner setting not found: " + id));
    }

    private ApplicationException error(HttpStatus s, String m) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, s, m);
    }
}
