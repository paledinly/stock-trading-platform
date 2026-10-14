/*
 * 수동 거래 원장(사용자가 직접 적는 매수/매도 기록)과 투자 노트의 조회·추가·수정·삭제를 처리하는 파일이에요.
 * 저장할 때마다 전체 장부를 다시 계산해 "가진 것보다 많이 판" 기록이 생기지 않도록 막아요.
 * 실주문 API는 없고, TradeController가 이 서비스를 호출해요.
 */
package com.sunmo.stockplatform.trade.application;

import com.sunmo.stockplatform.common.error.*;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import com.sunmo.stockplatform.trade.api.*;
import com.sunmo.stockplatform.trade.domain.*;
import com.sunmo.stockplatform.trade.infrastructure.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/**
 * 거래 장부를 관리하는 서비스예요.
 * 가계부 관리인처럼 새 기록을 적고 고치고 지우되, 적을 때마다 장부 전체를 다시 맞춰 보고 앞뒤가 안 맞으면 그 수정을 되돌려요.
 * 클래스 전체가 트랜잭션(여러 작업을 '전부 성공하거나 전부 취소'하게 묶는 것)으로 묶여 있어서, 검증 실패 시 저장도 취소돼요.
 * 사용자는 한 명(OWNER=1)으로 고정돼 있어요.
 */
@Service
@Transactional
public class TradeService {
    private static final long OWNER = 1L;
    private final TradeRepository trades;
    private final InvestmentJournalRepository journals;
    private final StockRepository stocks;
    private final PortfolioCalculator calculator = new PortfolioCalculator();

    /**
     * 거래·노트·종목 레포지토리(DB에서 데이터를 꺼내고 넣는 창구)를 주입받아요.
     * 받는 것: 세 레포지토리. 돌려주는 것: 없음(생성자).
     */
    public TradeService(TradeRepository trades, InvestmentJournalRepository journals, StockRepository stocks) {
        this.trades = trades;
        this.journals = journals;
        this.stocks = stocks;
    }

    /**
     * 최근 거래를 최신순으로 돌려줘요.
     * 받는 것: 최대 개수(1~100으로 맞춰요). 돌려주는 것: 손익·보유일·노트가 붙은 거래 응답 목록.
     * 손익은 장부 전체를 시간순으로 재생해야 맞기 때문에, 화면에 보일 일부만이 아니라 전체를 먼저 계산해요.
     */
    @Transactional(readOnly = true)
    public List<TradeResponse> list(int limit) {
        List<Trade> ledger = trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER);
        var metrics = calculator.calculate(ledger, Instant.now());
        // 노트는 거래마다 한 번씩 따로 조회해요(최대 100건이라 감당 가능한 수준으로 보여요).
        return trades
                .findByOwnerIdOrderByTradedAtDescIdDesc(OWNER, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream()
                .map(t -> TradeResponse.from(t, journals.findByTradeId(t.getId()).orElse(null), metrics.get(t.getId())))
                .toList();
    }

    /**
     * 거래 한 건을 손익·노트와 함께 돌려줘요.
     * 받는 것: 거래 id. 돌려주는 것: 거래 응답(없으면 404 오류).
     */
    @Transactional(readOnly = true)
    public TradeResponse get(long id) {
        Trade t = require(id);
        var metrics = calculator.calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now());
        return TradeResponse.from(t, journals.findByTradeId(id).orElse(null), metrics.get(id));
    }

    /**
     * 새 거래를 기록해요.
     * 받는 것: 거래 내용, Idempotency-Key(같은 요청 중복 저장을 막는 꼬리표, 선택).
     * 돌려주는 것: 저장된 거래 응답. 같은 키로 이미 저장된 거래가 있으면 새로 만들지 않고 그 거래를 돌려줘요.
     */
    public TradeResponse create(TradeRequests.Create body, String key) {
        // 1) 중복 요청 확인: 같은 키가 있으면 기존 결과를 그대로 돌려줘요.
        if (key != null && !key.isBlank()) {
            var prior = trades.findByOwnerIdAndIdempotencyKey(OWNER, key.trim());
            if (prior.isPresent())
                return get(prior.get().getId());
        }
        // 2) 활성 종목인지 확인하고 저장해요.
        Stock stock = stocks.findByStockCodeAndActiveTrue(body.stockCode())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Stock not found: " + body.stockCode()));
        Trade trade = trades.save(new Trade(OWNER, stock, body.tradeType(), body.tradedAt(), body.price(),
                body.quantity(), blankToNull(key)));
        // 3) 장부 전체 검증: 실패하면 예외로 트랜잭션이 취소돼 방금 저장도 되돌려져요.
        validateLedger();
        return TradeResponse.from(trade, null, calculator
                .calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now()).get(trade.getId()));
    }

    /**
     * 거래 내용을 고쳐요.
     * 받는 것: 거래 id, 수정 내용(클라이언트가 본 버전 포함). 돌려주는 것: 수정된 거래 응답.
     * 그사이 다른 수정이 있었으면(버전 불일치) 409 오류를 내요.
     */
    public TradeResponse update(long id, TradeRequests.Update body) {
        Trade t = require(id);
        checkVersion(t.getVersion(), body.version());
        t.update(body.tradeType(), body.tradedAt(), body.price(), body.quantity());
        // 변경을 DB에 먼저 반영(flush)해야 장부 재조회 결과에 수정 내용이 들어가요.
        trades.flush();
        validateLedger();
        return get(id);
    }

    /**
     * 거래를 지워요.
     * 받는 것: 거래 id. 돌려주는 것: 없음.
     * 매수 기록을 지워서 이후 매도가 보유 수량을 넘게 되면 400 오류로 삭제를 취소해요.
     */
    public void delete(long id) {
        trades.delete(require(id));
        trades.flush();
        validateLedger();
    }

    /**
     * 거래에 붙는 투자 노트를 새로 만들거나 덮어써요.
     * 받는 것: 거래 id, 노트 내용(버전 포함). 돌려주는 것: 노트가 반영된 거래 응답.
     * 이유 코드는 대문자로 맞추고, 중복 코드나 설명 없는 CUSTOM 이유는 400 오류로 거절해요.
     */
    public TradeResponse putJournal(long id, TradeRequests.Journal body) {
        // 준비: 노트가 없으면 새로 만들고(버전 0), 버전이 다르면 다른 곳에서 먼저 고친 것이라 거절해요.
        Trade trade = require(id);
        InvestmentJournal journal = journals.findByTradeId(id).orElseGet(() -> new InvestmentJournal(trade));
        if (journal.getVersion() != body.version())
            throw error(HttpStatus.CONFLICT, "The journal changed; refresh and retry");
        // 처리: 이유 목록을 정리·검증해요(입력 순서는 유지).
        Set<String> codes = new HashSet<>();
        Set<TradeReason> reasons = new LinkedHashSet<>();
        for (var reason : body.reasons()) {
            String code = reason.code().trim().toUpperCase(Locale.ROOT);
            if (!codes.add(code))
                throw error(HttpStatus.BAD_REQUEST, "Duplicate reason: " + code);
            String custom = blankToNull(reason.customReason());
            if (code.equals("CUSTOM") && custom == null)
                throw error(HttpStatus.BAD_REQUEST, "CUSTOM reason requires customReason");
            reasons.add(new TradeReason(code, custom));
        }
        // 결과: 노트 내용을 바꿔 저장하고, 최신 거래 응답을 돌려줘요.
        journal.update(blankToNull(body.memo()), body.targetPrice(), body.stopLossPrice(), reasons);
        journals.save(journal);
        return get(id);
    }

    /**
     * 장부 전체를 다시 계산해 앞뒤가 맞는지 확인해요.
     * 받는 것: 없음. 돌려주는 것: 없음(문제가 있으면 400 오류를 던져요).
     */
    private void validateLedger() {
        try {
            calculator.calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now());
        } catch (IllegalArgumentException e) {
            throw error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 이 사용자의 거래를 id로 찾아요.
     * 받는 것: 거래 id. 돌려주는 것: 거래(없으면 404 오류).
     */
    private Trade require(long id) {
        return trades.findByIdAndOwnerId(id, OWNER)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Trade not found: " + id));
    }

    /**
     * 저장된 버전과 요청 버전이 같은지 확인해요.
     * 받는 것: 실제 버전, 요청이 기대한 버전. 돌려주는 것: 없음(다르면 409 오류).
     */
    private void checkVersion(long actual, long expected) {
        if (actual != expected)
            throw error(HttpStatus.CONFLICT, "The trade changed; refresh and retry");
    }

    /**
     * 빈 문자열을 null로 바꾸고, 값이 있으면 앞뒤 공백을 지워요.
     * 받는 것: 문자열. 돌려주는 것: 정리된 문자열 또는 null.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 공통 오류 형식(ApplicationException)을 만들어요.
     * 받는 것: HTTP 상태, 메시지. 돌려주는 것: 던질 예외 객체(코드는 항상 INVALID_REQUEST예요).
     */
    private ApplicationException error(HttpStatus status, String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message);
    }
}
