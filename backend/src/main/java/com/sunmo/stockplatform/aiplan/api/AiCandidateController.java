/*
 * AI 후보 평가(ADR-014) API예요. 명단·카드·프롬프트, 응답 검사·저장, 목록·상세, 채점 실행, 판정 요약을 제공해요.
 * Web의 'AI 장전' → '후보 평가' 탭이 불러요. 운영 마감 추천과 구독에는 영향을 주지 않아요.
 */
package com.sunmo.stockplatform.aiplan.api;

import com.sunmo.stockplatform.aiplan.candidate.AiCandidateOutcomeService;
import com.sunmo.stockplatform.aiplan.candidate.AiCandidatePoolService;
import com.sunmo.stockplatform.aiplan.candidate.AiCandidateService;
import com.sunmo.stockplatform.aiplan.candidate.AiCandidateValidator;
import com.sunmo.stockplatform.closing.application.ClosingTradingCalendar;
import org.springframework.core.io.ClassPathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** AI 후보 평가 창구예요. 봉인된 시험지(카드)를 내주고, 답안지를 받고, 채점표를 보여 줘요. */
@RestController
@RequestMapping("/api/v1/ai-candidates")
public class AiCandidateController {
    /** 카드 응답이에요. prompt는 지시문 + 예시 + 카드를 합친, ChatGPT에 그대로 붙여넣을 글이에요. */
    public record Card(String schemaVersion, AiCandidatePoolService.Pool pool, String prompt) { }

    private final AiCandidatePoolService pools;
    private final AiCandidateService evaluations;
    private final AiCandidateOutcomeService outcomes;
    private final ClosingTradingCalendar calendar;

    public AiCandidateController(AiCandidatePoolService pools, AiCandidateService evaluations,
            AiCandidateOutcomeService outcomes, ClosingTradingCalendar calendar) {
        this.pools = pools;
        this.evaluations = evaluations;
        this.outcomes = outcomes;
        this.calendar = calendar;
    }

    /** 하는 일: GET /card. 다음 평가 거래일의 명단·카드·프롬프트예요. 고정 시간대(07:55~장 시작)에 처음 부르면 명단을 고정해요. */
    @GetMapping("/card")
    public Card card() {
        AiCandidatePoolService.Pool pool = pools.current();
        String example = read("ai-candidate/ai-candidate-v1.example.json").strip();
        String prompt = read("ai-candidate/ai-candidate-v1.prompt.md").replace("{{example}}", example)
                .replace("{{card}}", pool.cardText());
        return new Card(AiCandidateValidator.SCHEMA_VERSION, pool, prompt);
    }

    /** 하는 일: POST /. 붙여넣은 JSON을 검사해 저장해요. 저장하면 201, 검사에 실패하면 422와 오류 목록이에요. */
    @PostMapping
    public ResponseEntity<AiCandidateService.SubmitResult> submit(@RequestBody String raw) {
        AiCandidateService.SubmitResult result = evaluations.submit(raw);
        return ResponseEntity.status(result.saved() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    /** 하는 일: POST /check. 저장하지 않고 검사만 해요. */
    @PostMapping("/check")
    public AiCandidateService.SubmitResult check(@RequestBody String raw) {
        return evaluations.check(raw);
    }

    /** 하는 일: GET /?from&to. 모든 판 목록이에요. 기본은 최근 90일이에요. */
    @GetMapping
    public List<AiCandidateService.EvalSummary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? calendar.today().plusDays(7) : to;
        return evaluations.list(from == null ? end.minusDays(97) : from, end);
    }

    /** 하는 일: GET /{id}. 원본 JSON, 명단 순서대로 등급·채점 결과, 같은 날 다른 판이에요. */
    @GetMapping("/{id}")
    public AiCandidateService.EvalDetail detail(@PathVariable long id) {
        return evaluations.detail(id);
    }

    /** 하는 일: POST /evaluate. 채점을 바로 돌려요. 돌려주는 것: 채점한 종목 수. */
    @PostMapping("/evaluate")
    public int evaluate() {
        return outcomes.evaluate();
    }

    /** 하는 일: GET /summary. 바구니 비교와 고르기·수익·걸러내기 판정(처음 40채점일) 진행 상황이에요. */
    @GetMapping("/summary")
    public AiCandidateOutcomeService.Summary summary() {
        return outcomes.summary();
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
