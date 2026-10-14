/*
 * KIS 종목 마스터 파일을 내려받아 파싱하고, DB의 종목 테이블을 최신 내용으로 맞추는 파일이에요.
 * 새 종목은 추가하고, 이미 있는 종목은 이름·시장·관리/정지 상태를 갱신해요.
 * StockMasterBootstrap(시작 시), StockMasterController(수동), 그리고 kis 쪽 마스터 동기화 스케줄러가 사용해요.
 */
package com.sunmo.stockplatform.stock.application;

import com.sunmo.stockplatform.kis.master.KisMasterFileClient;
import com.sunmo.stockplatform.kis.master.KisMasterParser;
import com.sunmo.stockplatform.stock.domain.Market;
import com.sunmo.stockplatform.stock.domain.Stock;
import com.sunmo.stockplatform.stock.infrastructure.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 종목 마스터를 KIS 공식 목록과 맞춰 주는 서비스예요.
 * 학교에서 새 학기 공식 명단을 받아 와 반 출석부에 전학생은 추가하고, 이름이 바뀐 학생은 고쳐 적는 담임 선생님과 같아요.
 */
@Service
public class StockMasterSyncService {
    private final KisMasterFileClient fileClient;
    private final KisMasterParser parser;
    private final StockRepository repository;
    private final Clock clock = Clock.systemUTC();

    /**
     * 마스터 파일 다운로더, 파서, 종목 레포지토리를 주입받아요.
     * 받는 것: 파일 클라이언트, 파서, 레포지토리. 돌려주는 것: 없음(생성자).
     */
    public StockMasterSyncService(KisMasterFileClient fileClient, KisMasterParser parser, StockRepository repository) {
        this.fileClient = fileClient;
        this.parser = parser;
        this.repository = repository;
    }

    /**
     * 한 시장(KOSPI 또는 KOSDAQ)의 마스터 파일을 받아 종목 테이블에 반영해요.
     * 트랜잭션(여러 작업을 '전부 성공하거나 전부 취소'하게 묶는 것)으로 묶여 있어요.
     * 받는 것: 시장. 돌려주는 것: 파일에서 읽은 종목 수.
     * ⚠️ 확인 필요: 마스터 파일에서 사라진(상장폐지 등) 종목을 비활성(active=false)으로 바꾸는 처리가 없어요.
     * 한 번 들어온 종목은 계속 active로 남아 검색·스캔 대상에 남는 것으로 보여요.
     */
    @Transactional
    public int synchronize(Market market) {
        // 준비: 이번 동기화 시각을 하나로 정하고, 파일을 받아 종목 목록으로 파싱해요.
        var synchronizedAt = clock.instant();
        var masterStocks = parser.parse(fileClient.download(market), market);
        // 처리: 종목코드로 기존 행을 찾고, 없으면 새로 만들고 있으면 내용을 갱신해요.
        for (var master : masterStocks) {
            var stock = repository.findByStockCode(master.stockCode())
                    .orElseGet(() -> new Stock(master.stockCode(), master.standardCode(), master.stockName(),
                            master.market(), master.marketType(), master.managed(), master.tradingHalted(),
                            synchronizedAt));
            // id가 있으면 DB에 이미 있던 종목이라 최신 값으로 덮어써요(새로 만든 객체는 생성자에서 이미 채워졌어요).
            if (stock.getId() != null) {
                stock.synchronize(master.standardCode(), master.stockName(), master.market(), master.marketType(),
                        master.managed(), master.tradingHalted(), synchronizedAt);
            }
            repository.save(stock);
        }
        return masterStocks.size();
    }

    /**
     * KOSPI와 KOSDAQ를 차례로 동기화해요.
     * 받는 것: 없음. 돌려주는 것: 두 시장에서 처리한 종목 수의 합.
     * ⚠️ 확인 필요: 같은 클래스 안에서 synchronize()를 직접 부르기 때문에(self-invocation)
     * Spring 프록시를 거치지 않아 @Transactional이 적용되지 않는 것으로 보여요.
     * 그러면 시장 단위로 묶이지 않고 save가 한 건씩 따로 커밋되고, 중간 실패 시 일부만 반영될 수 있어요.
     */
    public int synchronizeAll() {
        return synchronize(Market.KOSPI) + synchronize(Market.KOSDAQ);
    }
}
