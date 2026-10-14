/*
 * 사용자가 직접 기록하는 거래(매수/매도) 원장과 투자 노트를 다루는 REST API(웹 주소로 데이터를 주고받는 창구) 파일이에요.
 * 실제 주문을 내지 않고, 이미 한 거래를 손으로 적어 두는 수동 기록용이에요.
 * Web의 투자기록 화면(JournalPage)이 이 API를 호출해요.
 */
package com.sunmo.stockplatform.trade.api;

import com.sunmo.stockplatform.trade.application.TradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 거래 기록의 조회·추가·수정·삭제와 투자 노트 저장 요청을 받는 창구예요.
 * 가계부 앱의 입력 화면처럼 "이날 이 가격에 몇 주 샀다/팔았다"를 적고 고치고 지우는 일을 받아 서비스에 넘겨요.
 */
@Validated
@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {
    private final TradeService service;

    /**
     * 의존성 주입(필요한 부품을 Spring이 대신 만들어 넣어 주는 것)으로 거래 서비스를 받아요.
     * 받는 것: 거래 서비스. 돌려주는 것: 없음(생성자).
     */
    public TradeController(TradeService service) {
        this.service = service;
    }

    /**
     * 최근 거래 기록을 최신순으로 돌려줘요.
     * 받는 것: 최대 개수(1~100, 기본 50). 돌려주는 것: 거래 응답 목록(실현손익·보유일 포함).
     */
    @GetMapping
    public List<TradeResponse> list(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(limit);
    }

    /**
     * 새 거래 기록을 추가해요.
     * 받는 것: 거래 내용, 선택적인 Idempotency-Key 헤더(같은 요청이 두 번 와도 한 번만 저장되게 하는 꼬리표).
     * 돌려주는 것: 저장된 거래(201 Created).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse create(@Valid @RequestBody TradeRequests.Create body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.create(body, key);
    }

    /**
     * 거래 한 건을 조회해요.
     * 받는 것: 거래 id. 돌려주는 것: 거래 응답(노트 포함).
     */
    @GetMapping("/{id}")
    public TradeResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * 거래 한 건의 내용을 고쳐요.
     * 받는 것: 거래 id, 수정 내용(버전 포함). 돌려주는 것: 수정된 거래 응답.
     */
    @PatchMapping("/{id}")
    public TradeResponse update(@PathVariable long id, @Valid @RequestBody TradeRequests.Update body) {
        return service.update(id, body);
    }

    /**
     * 거래 한 건을 지워요.
     * 받는 것: 거래 id. 돌려주는 것: 없음(204 No Content).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /**
     * 거래에 붙는 투자 노트(메모, 목표가, 손절가, 매매 이유)를 저장하거나 덮어써요.
     * 받는 것: 거래 id, 노트 내용(버전 포함). 돌려주는 것: 노트가 반영된 거래 응답.
     */
    @PutMapping("/{id}/journal")
    public TradeResponse journal(@PathVariable long id, @Valid @RequestBody TradeRequests.Journal body) {
        return service.putJournal(id, body);
    }
}
