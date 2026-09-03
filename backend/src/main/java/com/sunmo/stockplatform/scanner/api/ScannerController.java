package com.sunmo.stockplatform.scanner.api;

import com.sunmo.stockplatform.scanner.application.ScannerQueryService;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ScannerController {
    private final ScannerQueryService service;

    public ScannerController(ScannerQueryService service) {
        this.service = service;
    }

    @GetMapping("/scanner-settings")
    public List<ScannerDtos.SettingResponse> settings() {
        return service.settings();
    }

    @PostMapping("/scanner-settings")
    @ResponseStatus(HttpStatus.CREATED)
    public ScannerDtos.SettingResponse create(@Valid @RequestBody ScannerDtos.SettingRequest body) {
        return service.create(body);
    }

    @PatchMapping("/scanner-settings/{id}")
    public ScannerDtos.SettingResponse update(@PathVariable long id,
            @Valid @RequestBody ScannerDtos.SettingRequest body) {
        return service.update(id, body);
    }

    @DeleteMapping("/scanner-settings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @GetMapping("/scanner-detections")
    public List<ScannerDtos.DetectionResponse> detections(
            @RequestParam(required = false) ScannerType type,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "true") boolean todayOnly) {
        return service.detections(type, limit, todayOnly);
    }

    @GetMapping("/scanner-detections/{id}")
    public ScannerDtos.DetectionResponse detection(@PathVariable long id) {
        return service.detection(id);
    }

    @GetMapping("/scanners/{kind}")
    public List<ScannerDtos.DetectionResponse> snapshot(@PathVariable String kind,
            @RequestParam(defaultValue = "20") int limit) {
        return service.detections(type(kind), limit, true);
    }

    private ScannerType type(String kind) {
        return switch (kind) {
            case "volume" -> ScannerType.VOLUME;
            case "price-rise" -> ScannerType.PRICE_RISE;
            case "momentum" -> ScannerType.MOMENTUM;
            case "volume-breakout" -> ScannerType.VOLUME_BREAKOUT;
            case "turnover-breakout" -> ScannerType.TURNOVER_BREAKOUT;
            case "high-breakout" -> ScannerType.HIGH_BREAKOUT;
            case "vwap-breakout" -> ScannerType.VWAP_BREAKOUT;
            case "vwap-reclaim" -> ScannerType.VWAP_RECLAIM;
            case "pullback-rebreak" -> ScannerType.PULLBACK_REBREAK;
            default -> throw new IllegalArgumentException("Unknown scanner");
        };
    }
}
