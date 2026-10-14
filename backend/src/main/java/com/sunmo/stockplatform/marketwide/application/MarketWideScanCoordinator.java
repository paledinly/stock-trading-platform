/*
 * 시장 전체 스캔이 동시에 두 번 돌지 않도록 순서를 잡아 주는 파일이에요.
 * 수동 스캔은 바로 실행하고, 자동(회차) 스캔은 실행 기록을 DB에 남겨 같은 회차를 두 번 끝내지 않게 해요.
 * MarketWideScannerController(수동)와 MarketWideScanScheduler(자동)가 사용해요.
 */
package com.sunmo.stockplatform.marketwide.application;

import com.sunmo.stockplatform.common.error.ApplicationException;
import com.sunmo.stockplatform.common.error.ErrorCode;
import com.sunmo.stockplatform.marketwide.api.MarketWideDtos.BroadScanResponse;
import com.sunmo.stockplatform.marketwide.domain.MarketWideScanRun;
import com.sunmo.stockplatform.marketwide.infrastructure.MarketWideScanRunRepository;
import com.sunmo.stockplatform.stock.domain.Market;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 시장 전체 스캔 실행을 한 번에 하나씩만 허락하는 조정자예요.
 * 화장실 "사용 중" 표시판처럼, 누가 스캔 중이면 다음 사람은 기다리지 않고 돌아가게 해요.
 */
@Service
public class MarketWideScanCoordinator {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private final MarketWideScannerService scanner;
    private final MarketWideScanRunRepository runs;
    private final MarketWideDiagnostics diagnostics;
    // 스캔 실행 중 여부. 여러 스레드가 동시에 바꿔도 안전한 원자적(한 번에 처리되는) 플래그예요.
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 하는 일: 실제 스캔 서비스, 실행 기록 레포지토리(DB 창구), 진단 기록기를 받아 둬요.
     * 받는 것: 위 세 협력 객체.
     */
    public MarketWideScanCoordinator(MarketWideScannerService scanner, MarketWideScanRunRepository runs,
            MarketWideDiagnostics diagnostics) {
        this.scanner = scanner;
        this.runs = runs;
        this.diagnostics = diagnostics;
    }

    /**
     * 하는 일: 사용자가 요청한 수동 스캔을 실행해요. 이미 실행 중이면 409(CONFLICT) 오류를 내요.
     * 받는 것: 시장(null이면 전체로 보여요), 스캔 개수, 후보 개수, ETF 포함 여부.
     * 돌려주는 것: 스캔 결과 응답.
     */
    public BroadScanResponse manual(Market market, int limit, int candidates, boolean includeEtf) {
        if (!running.compareAndSet(false, true))
            throw new ApplicationException(ErrorCode.INVALID_REQUEST, HttpStatus.CONFLICT,
                    "Market-wide scan is already running");
        Instant started = Instant.now();
        diagnostics.started(started, null);
        try {
            BroadScanResponse response = scanner.scan(market, limit, candidates, includeEtf);
            diagnostics.completed(Instant.now(), response);
            return response;
        } catch (RuntimeException error) {
            diagnostics.failed(Instant.now(), rootMessage(error));
            throw error;
        } finally {
            // 성공/실패와 관계없이 "사용 중" 표시를 풀어요.
            running.set(false);
        }
    }

    /**
     * 하는 일: 스케줄러가 부른 회차 스캔을 실행하고, 결과를 회차 실행 기록으로 저장해요.
     * 이미 완료된 회차이거나 다른 스캔이 돌고 있으면 건너뛰어요.
     * 받는 것: 회차 시각(bucket), 스캔 개수, 후보 개수, ETF 포함 여부.
     * 돌려주는 것: 없음. 실패해도 예외를 밖으로 던지지 않고 기록만 남겨요.
     */
    public void scheduled(Instant bucket, int limit, int candidates, boolean includeEtf) {
        // 준비: 같은 회차를 이미 끝냈는지, 지금 다른 스캔이 도는지 확인해요.
        MarketWideScanRun run = runs.findByScheduledFor(bucket).orElse(null);
        if (run != null && run.getStatus() == MarketWideScanRun.Status.COMPLETED) {
            diagnostics.skipped("BUCKET_ALREADY_COMPLETED", bucket);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            diagnostics.skipped("SCAN_ALREADY_RUNNING", bucket);
            return;
        }
        // 실행 기록 저장부터 스캔·결과 저장까지 모두 try/finally 안에 두어, 어디서 예외가 나도
        // running 표시가 반드시 풀리게 해요. 그래야 다음 회차가 SCAN_ALREADY_RUNNING으로 막히지 않아요.
        Instant started = Instant.now();
        diagnostics.started(started, bucket);
        try {
            // 실패했던 회차면 다시 시작으로 표시하고, 처음이면 새 실행 기록을 만들어요.
            if (run == null)
                run = new MarketWideScanRun(bucket.atZone(MARKET_ZONE).toLocalDate(), bucket, started);
            else
                run.restart(started);
            // 시작 기록 저장이 실패하면 스캔은 돌리지 않고 실패로만 진단에 남겨요(회차는 나중에 다시 시도돼요).
            run = runs.save(run);
        } catch (RuntimeException error) {
            diagnostics.failed(Instant.now(), "RUN_RECORD_SAVE_FAILED: " + rootMessage(error));
            running.set(false);
            return;
        }
        // 처리: 전체 시장 스캔을 돌리고 결과를 회차 기록과 진단에 남겨요.
        try {
            BroadScanResponse response = scanner.scan(null, limit, candidates, includeEtf);
            Instant completed = Instant.now();
            run.complete(completed, response.scannedCount(), response.candidateCount(), response.fallback());
            runs.save(run);
            diagnostics.completed(completed, response);
        } catch (RuntimeException error) {
            Instant failed = Instant.now();
            String message = rootMessage(error);
            // 실패 기록 저장마저 실패해도 예외를 밖으로 던지지 않고, 그 사실을 진단 메시지에 덧붙여요.
            try {
                run.fail(failed, message);
                runs.save(run);
            } catch (RuntimeException saveError) {
                message = message + " (run record save failed: " + rootMessage(saveError) + ")";
            }
            diagnostics.failed(failed, message);
        } finally {
            running.set(false);
        }
    }

    /**
     * 하는 일: 예외의 가장 안쪽 원인을 찾아 그 메시지를 꺼내요.
     * 받는 것: 발생한 예외.
     * 돌려주는 것: 원인 메시지(없으면 예외 클래스 이름).
     */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
