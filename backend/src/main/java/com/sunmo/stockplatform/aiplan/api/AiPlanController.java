/*
 * AI 장전 계획(ADR-012) API예요. 붙여넣은 JSON 검사·저장, 목록·상세 조회, 채점 실행, 1단계 기준 진행 상황, 프롬프트 양식을 제공해요.
 * 실제 처리는 AiPlanService(저장·조회)와 AiPlanOutcomeService(채점·집계)가 해요. Web의 'AI 장전' 화면이 불러요.
 */
package com.sunmo.stockplatform.aiplan.api;

import com.sunmo.stockplatform.aiplan.application.AiPlanOutcomeService;
import com.sunmo.stockplatform.aiplan.application.AiPlanService;
import com.sunmo.stockplatform.aiplan.application.AiPlanValidator;
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

/** AI 장전 계획 창구예요. 답안지를 받는 접수처이자, 채점표를 보여 주는 게시판이에요. */
@RestController
@RequestMapping("/api/v1/ai-plans")
public class AiPlanController {
    /** ChatGPT에 붙여넣을 프롬프트와 JSON 예시예요. targetDate는 다음에 분석할 거래일이에요. */
    public record Template(String schemaVersion, LocalDate targetDate, String prompt, String example) { }

    private final AiPlanService plans;
    private final AiPlanOutcomeService outcomes;
    private final ClosingTradingCalendar calendar;

    /** 하는 일: 저장·채점 서비스와 거래일 달력을 주입받아요. */
    public AiPlanController(AiPlanService plans, AiPlanOutcomeService outcomes, ClosingTradingCalendar calendar) {
        this.plans = plans;
        this.outcomes = outcomes;
        this.calendar = calendar;
    }

    /** 하는 일: POST /. 본문은 붙여넣은 JSON 원문이에요. 저장했으면 201, 검사에 실패하면 422와 오류 목록이에요. */
    @PostMapping
    public ResponseEntity<AiPlanService.SubmitResult> submit(@RequestBody String raw) {
        AiPlanService.SubmitResult result = plans.submit(raw);
        return ResponseEntity.status(result.saved() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
    }

    /** 하는 일: POST /check. 저장하지 않고 검사만 해요. 항상 200이고 오류·경고를 돌려줘요. */
    @PostMapping("/check")
    public AiPlanService.SubmitResult check(@RequestBody String raw) {
        return plans.check(raw);
    }

    /** 하는 일: GET /?from&to. 날짜별 최신 판 목록이에요. 기본은 최근 60일이에요. */
    @GetMapping
    public List<AiPlanService.PlanSummary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? calendar.today().plusDays(7) : to;
        return plans.list(from == null ? end.minusDays(67) : from, end);
    }

    /** 하는 일: GET /{id}. 원본 JSON, 종목별 채점 결과, 같은 날짜의 모든 판이에요. */
    @GetMapping("/{id}")
    public AiPlanService.PlanDetail detail(@PathVariable long id) {
        return plans.detail(id);
    }

    /** 하는 일: POST /evaluate. 채점을 바로 돌려요. 돌려주는 것: 채점한 종목 수. */
    @PostMapping("/evaluate")
    public int evaluate() {
        return outcomes.evaluate();
    }

    /** 하는 일: GET /summary. 1단계 기준(20일 중 12일 이상, 평균 초과 > 0) 진행 상황과 누적 통계예요. */
    @GetMapping("/summary")
    public AiPlanOutcomeService.Summary summary() {
        return outcomes.summary();
    }

    /** 하는 일: GET /template. 다음 분석 거래일을 채운 ai-premarket-v2 프롬프트와 JSON 예시예요. */
    @GetMapping("/template")
    public Template template() {
        LocalDate today = calendar.today();
        LocalDate target = calendar.isTradingDay(today) && calendar.now().isBefore(calendar.open(today))
                ? today : calendar.nextTradingDay(today);
        // 규격 v2 프롬프트(사용자가 정한 원문)와 예시에는 날짜가 2026-11-11로 적혀 있어요. 분석할 거래일로 바꿔 넣어요.
        String date = target.toString(), compact = date.replace("-", "");
        String example = read("ai-premarket/ai-premarket-v2.example.json").replace("2026-11-11", date)
                .replace("20261111", compact);
        String prompt = read("ai-premarket/ai-premarket-v2.prompt.md").replace("{{example}}", example.strip())
                .replace("2026-11-11", date).replace("20261111", compact);
        return new Template(AiPlanValidator.SCHEMA_VERSION, target, prompt, example);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
