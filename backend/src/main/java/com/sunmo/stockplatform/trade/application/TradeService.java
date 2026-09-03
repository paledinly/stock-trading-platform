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

@Service
@Transactional
public class TradeService {
    private static final long OWNER = 1L;
    private final TradeRepository trades;
    private final InvestmentJournalRepository journals;
    private final StockRepository stocks;
    private final PortfolioCalculator calculator = new PortfolioCalculator();

    public TradeService(TradeRepository trades, InvestmentJournalRepository journals, StockRepository stocks) {
        this.trades = trades;
        this.journals = journals;
        this.stocks = stocks;
    }

    @Transactional(readOnly = true)
    public List<TradeResponse> list(int limit) {
        List<Trade> ledger = trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER);
        var metrics = calculator.calculate(ledger, Instant.now());
        return trades
                .findByOwnerIdOrderByTradedAtDescIdDesc(OWNER, PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .stream()
                .map(t -> TradeResponse.from(t, journals.findByTradeId(t.getId()).orElse(null), metrics.get(t.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TradeResponse get(long id) {
        Trade t = require(id);
        var metrics = calculator.calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now());
        return TradeResponse.from(t, journals.findByTradeId(id).orElse(null), metrics.get(id));
    }

    public TradeResponse create(TradeRequests.Create body, String key) {
        if (key != null && !key.isBlank()) {
            var prior = trades.findByOwnerIdAndIdempotencyKey(OWNER, key.trim());
            if (prior.isPresent())
                return get(prior.get().getId());
        }
        Stock stock = stocks.findByStockCodeAndActiveTrue(body.stockCode())
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Stock not found: " + body.stockCode()));
        Trade trade = trades.save(new Trade(OWNER, stock, body.tradeType(), body.tradedAt(), body.price(),
                body.quantity(), blankToNull(key)));
        validateLedger();
        return TradeResponse.from(trade, null, calculator
                .calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now()).get(trade.getId()));
    }

    public TradeResponse update(long id, TradeRequests.Update body) {
        Trade t = require(id);
        checkVersion(t.getVersion(), body.version());
        t.update(body.tradeType(), body.tradedAt(), body.price(), body.quantity());
        trades.flush();
        validateLedger();
        return get(id);
    }

    public void delete(long id) {
        trades.delete(require(id));
        trades.flush();
        validateLedger();
    }

    public TradeResponse putJournal(long id, TradeRequests.Journal body) {
        Trade trade = require(id);
        InvestmentJournal journal = journals.findByTradeId(id).orElseGet(() -> new InvestmentJournal(trade));
        if (journal.getVersion() != body.version())
            throw error(HttpStatus.CONFLICT, "The journal changed; refresh and retry");
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
        journal.update(blankToNull(body.memo()), body.targetPrice(), body.stopLossPrice(), reasons);
        journals.save(journal);
        return get(id);
    }

    private void validateLedger() {
        try {
            calculator.calculate(trades.findByOwnerIdOrderByTradedAtAscIdAsc(OWNER), Instant.now());
        } catch (IllegalArgumentException e) {
            throw error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Trade require(long id) {
        return trades.findByIdAndOwnerId(id, OWNER)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Trade not found: " + id));
    }

    private void checkVersion(long actual, long expected) {
        if (actual != expected)
            throw error(HttpStatus.CONFLICT, "The trade changed; refresh and retry");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApplicationException error(HttpStatus status, String message) {
        return new ApplicationException(ErrorCode.INVALID_REQUEST, status, message);
    }
}
