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

@Service
public class MicrostructureService {
    private final MicrostructureProperties policy;
    private final TrajectoryProperties trajectory;
    private final MicrostructureAggregator aggregator;
    private final MicrostructureStore store;
    private final ClosingTradingCalendar calendar;
    private final TransactionTemplate transaction;
    private String error;
    public MicrostructureService(MicrostructureProperties policy,TrajectoryProperties trajectory,MicrostructureStore store,
            ClosingTradingCalendar calendar,PlatformTransactionManager manager) {
        this.policy=policy;this.trajectory=trajectory;this.store=store;this.calendar=calendar;
        aggregator=new MicrostructureAggregator(policy);transaction=new TransactionTemplate(manager);
    }
    @TransactionalEventListener
    public void receive(ObservedMarketTick event) {
        if(policy.enabled()&&trajectory.enabled()&&calendar.isTradingDay(event.tick().businessDate()))aggregator.tick(event);
    }
    public void book(MicrostructureModel.Book book) {
        if(policy.enabled()&&policy.orderbookEnabled()&&trajectory.enabled()
                &&calendar.isTradingDay(book.receivedAt().atZone(ClosingTradingCalendar.ZONE).toLocalDate()))aggregator.book(book);
    }
    @Scheduled(fixedDelayString="${closing.trajectory.flush-interval:1s}",scheduler="trajectoryScheduler")
    public synchronized void flush() {
        if(!policy.enabled()||!trajectory.enabled())return;
        var rows=aggregator.ready(calendar.now());
        if(rows.isEmpty())return;
        try {transaction.executeWithoutResult(s->rows.forEach(store::save));aggregator.acknowledge(rows);error=null;}
        catch(RuntimeException failure){error="MICROSTRUCTURE_PERSIST_FAILED";}
    }
    public TrajectoryModel.Snapshot enrich(TrajectoryModel.Snapshot snapshot) {
        if(!policy.enabled())return snapshot;
        return MicrostructureFeatures.enrich(snapshot,store.minutes(snapshot.symbol(),snapshot.timestamp().minusSeconds(120),snapshot.timestamp()));
    }
    public synchronized Map<String,Object> status(){return fields("enabled",policy.enabled(),"orderbookEnabled",policy.orderbookEnabled(),
            "trajectoryEnabled",trajectory.enabled(),"rejectedObservations",aggregator.rejected(),"error",error,
            "largeTradeThreshold",policy.largeTradeThreshold(),"pollIntervalSeconds",policy.pollInterval().toSeconds(),"maxSymbols",policy.maxSymbols());}
}
