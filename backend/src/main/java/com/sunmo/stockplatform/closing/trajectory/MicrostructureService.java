/*
 * 미세구조(호가·큰 체결) 연구 수집의 중심 서비스예요. 체결 틱과 호가 샘플을 집계기에 넘기고, 1초마다 확정된 1분 집계를 DB에 저장해요.
 * Trajectory 스냅샷을 만들 때 직전 1분 미세구조 지표를 덧붙이는 enrich도 제공해요.
 * TrajectoryService, OrderbookCollector, TrajectoryController가 사용해요. 기본 꺼짐이에요.
 */
package com.sunmo.stockplatform.closing.trajectory;

import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import com.sunmo.stockplatform.market.application.ObservedMarketTick;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import static com.sunmo.stockplatform.closing.trajectory.TrajectoryModel.fields;

/**
 * 호가 사진과 큰 체결을 1분 단위로 정리해 보관하고, 스냅샷에 붙여 주는 서비스예요.
 * 사진사가 찍어 온 사진(호가)과 현장 기록(체결)을 1분 앨범으로 정리해 두고, 요약표(스냅샷)에 해당 페이지를 끼워 주는 역할이에요.
 */
@Service
public class MicrostructureService {
    private final MicrostructureProperties policy;
    private final TrajectoryProperties trajectory;
    private final MicrostructureAggregator aggregator;
    private final MicrostructureStore store;
    private final ClosingTradingCalendar calendar;
    private final TransactionTemplate transaction;
    private String error;
    /** 하는 일: 설정·저장소·달력을 받고 집계기와 트랜잭션(한 묶음 DB 작업) 도우미를 만들어요. 받는 것: 각 의존 객체. 돌려주는 것: 서비스 객체예요. */
    public MicrostructureService(MicrostructureProperties policy,TrajectoryProperties trajectory,MicrostructureStore store,
            ClosingTradingCalendar calendar,PlatformTransactionManager manager) {
        this.policy=policy;this.trajectory=trajectory;this.store=store;this.calendar=calendar;
        aggregator=new MicrostructureAggregator(policy);transaction=new TransactionTemplate(manager);
    }
    /** 하는 일: 체결 틱 이벤트를 받아(커밋 후) 수집이 켜져 있고 거래일이면 큰 체결 집계에 넘겨요. 받는 것: ObservedMarketTick. 돌려주는 것: 없어요. */
    @TransactionalEventListener(fallbackExecution = true)
    public void receive(ObservedMarketTick event) {
        if(policy.enabled()&&trajectory.enabled()&&calendar.isTradingDay(event.tick().businessDate()))aggregator.tick(event);
    }
    /** 하는 일: 호가 샘플을 받아 호가 수집이 켜져 있고 거래일이면 집계기에 넘겨요. 받는 것: Book. 돌려주는 것: 없어요. */
    public void book(MicrostructureModel.Book book) {
        if(policy.enabled()&&policy.orderbookEnabled()&&trajectory.enabled()
                &&calendar.isTradingDay(book.receivedAt().atZone(ClosingTradingCalendar.ZONE).toLocalDate()))aggregator.book(book);
    }
    /**
     * 하는 일: (기본 1초마다, TrajectoryService.flush에서도 호출) 확정된 미세구조 1분 집계를 한 트랜잭션으로 저장하고 확인 처리해요.
     * 받는 것: 없어요. 돌려주는 것: 없어요. 실패하면 오류 표시만 남기고 다음 번에 다시 시도해요(로그는 남기지 않아요).
     */
    @Scheduled(fixedDelayString="${closing.trajectory.flush-interval:1s}",scheduler="trajectoryScheduler")
    public synchronized void flush() {
        if(!policy.enabled()||!trajectory.enabled())return;
        var rows=aggregator.ready(calendar.now());
        if(rows.isEmpty())return;
        try {transaction.executeWithoutResult(s->rows.forEach(store::save));aggregator.acknowledge(rows);error=null;}
        catch(RuntimeException failure){error="MICROSTRUCTURE_PERSIST_FAILED";}
    }
    /**
     * 하는 일: 스냅샷 기준시각 직전 2분의 미세구조 1분 집계를 읽어 스냅샷에 호가·큰 체결 지표를 덧붙여요.
     * 받는 것: 스냅샷이에요. 돌려주는 것: 덧붙인 새 스냅샷, 수집이 꺼져 있으면 원래 스냅샷이에요.
     */
    public TrajectoryModel.Snapshot enrich(TrajectoryModel.Snapshot snapshot) {
        if(!policy.enabled())return snapshot;
        return MicrostructureFeatures.enrich(snapshot,store.minutes(snapshot.symbol(),snapshot.timestamp().minusSeconds(120),snapshot.timestamp()));
    }
    /** 하는 일: 수집 설정, 거부된 관측 수, 마지막 오류를 돌려줘요. 받는 것: 없어요. 돌려주는 것: 상태 Map이에요. */
    public synchronized Map<String,Object> status(){return fields("enabled",policy.enabled(),"orderbookEnabled",policy.orderbookEnabled(),
            "trajectoryEnabled",trajectory.enabled(),"rejectedObservations",aggregator.rejected(),"error",error,
            "largeTradeThreshold",policy.largeTradeThreshold(),"pollIntervalSeconds",policy.pollInterval().toSeconds(),"maxSymbols",policy.maxSymbols());}
}
