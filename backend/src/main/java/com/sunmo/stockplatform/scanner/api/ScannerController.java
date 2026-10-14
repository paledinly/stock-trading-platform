/*
 * 스캐너 설정과 감지 결과를 주고받는 REST API(웹 화면이 부르는 주소 모음) 컨트롤러예요.
 * 설정 목록·생성·수정·삭제와 감지 목록·단건 조회 주소를 열어 둬요.
 * 웹 프런트엔드의 스캐너 화면이 이 주소들을 호출해요.
 */
package com.sunmo.stockplatform.scanner.api;

import com.sunmo.stockplatform.scanner.application.ScannerQueryService;
import com.sunmo.stockplatform.scanner.domain.ScannerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 스캐너 설정과 감지 기록 요청을 받아 서비스에 넘기는 창구 클래스예요.
 * 은행 창구 직원이 손님의 요청서를 받아 안쪽 담당자(ScannerQueryService)에게 그대로 넘겨 주는 것과 같아요.
 * 입력값 검사(@Valid, @Min/@Max)는 여기서 먼저 해요.
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ScannerController {
    private final ScannerQueryService service;

    /**
     * 하는 일: 설정·감지 조회 서비스를 받아 보관해요.
     * 받는 것: service(ScannerQueryService).
     * 돌려주는 것: 없음(생성자).
     */
    public ScannerController(ScannerQueryService service) {
        this.service = service;
    }

    /**
     * 하는 일: 저장된 스캐너 설정 전체를 보여 줘요.
     * 받는 것: 없음.
     * 돌려주는 것: 설정 응답 목록.
     */
    @GetMapping("/scanner-settings")
    public List<ScannerDtos.SettingResponse> settings() {
        return service.settings();
    }

    /**
     * 하는 일: 새 스캐너 설정을 만들어요.
     * 받는 것: body(검사를 통과한 설정 요청).
     * 돌려주는 것: 만들어진 설정(HTTP 201).
     */
    @PostMapping("/scanner-settings")
    @ResponseStatus(HttpStatus.CREATED)
    public ScannerDtos.SettingResponse create(@Valid @RequestBody ScannerDtos.SettingRequest body) {
        return service.create(body);
    }

    /**
     * 하는 일: 기존 설정을 고쳐요.
     * 받는 것: id(설정 번호), body(요청 본문, 동시 수정 확인용 version 포함).
     * 돌려주는 것: 고친 설정. version이 다르면 409 충돌이 나요.
     */
    @PatchMapping("/scanner-settings/{id}")
    public ScannerDtos.SettingResponse update(@PathVariable long id,
            @Valid @RequestBody ScannerDtos.SettingRequest body) {
        return service.update(id, body);
    }

    /**
     * 하는 일: 설정을 지워요.
     * 받는 것: id(설정 번호).
     * 돌려주는 것: 없음(HTTP 204).
     */
    @DeleteMapping("/scanner-settings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /**
     * 하는 일: 감지 기록을 최신순으로 보여 줘요.
     * 받는 것: type(스캐너 종류, 생략하면 전체), limit(1~100개), todayOnly(한국 시간 오늘 것만 볼지).
     * 돌려주는 것: 감지 응답 목록.
     */
    @GetMapping("/scanner-detections")
    public List<ScannerDtos.DetectionResponse> detections(
            @RequestParam(required = false) ScannerType type,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "true") boolean todayOnly) {
        return service.detections(type, limit, todayOnly);
    }

    /**
     * 하는 일: 감지 기록 한 건을 보여 줘요.
     * 받는 것: id(감지 번호).
     * 돌려주는 것: 감지 응답. 없으면 404예요.
     */
    @GetMapping("/scanner-detections/{id}")
    public ScannerDtos.DetectionResponse detection(@PathVariable long id) {
        return service.detection(id);
    }
}
