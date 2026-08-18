package com.sunmo.stockplatform.stock.application;

import com.sunmo.stockplatform.kis.master.KisMasterFileClient;
import com.sunmo.stockplatform.kis.master.KisMasterParser;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class StockMasterSyncService {
    private final KisMasterFileClient fileClient;
    private final KisMasterParser parser;
    private final StockRepository repository;
    private final Clock clock = Clock.systemUTC();

    public StockMasterSyncService(KisMasterFileClient fileClient, KisMasterParser parser, StockRepository repository) {
        this.fileClient = fileClient;
        this.parser = parser;
        this.repository = repository;
    }

    @Transactional
    public int synchronize(Market market) {
        var synchronizedAt = clock.instant();
        var masterStocks = parser.parse(fileClient.download(market), market);
        for (var master : masterStocks) {
            var stock = repository.findByStockCode(master.stockCode())
                    .orElseGet(() -> new Stock(master.stockCode(), master.standardCode(), master.stockName(),
                            master.market(), master.marketType(), master.managed(), master.tradingHalted(), synchronizedAt));
            if (stock.getId() != null) {
                stock.synchronize(master.standardCode(), master.stockName(), master.market(), master.marketType(),
                        master.managed(), master.tradingHalted(), synchronizedAt);
            }
            repository.save(stock);
        }
        return masterStocks.size();
    }

    public int synchronizeAll() {
        return synchronize(Market.KOSPI) + synchronize(Market.KOSDAQ);
    }
}

