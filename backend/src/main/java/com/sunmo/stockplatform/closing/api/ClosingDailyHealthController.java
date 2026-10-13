package com.sunmo.stockplatform.closing.api;

import com.sunmo.stockplatform.closing.application.ClosingDailyHealthService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/closing-recommendations")
public class ClosingDailyHealthController {
    private final ClosingDailyHealthService health;

    public ClosingDailyHealthController(ClosingDailyHealthService health) {
        this.health = health;
    }

    /** Newest first; trading days without a recorded row are returned as MISSING. */
    @GetMapping("/daily-health")
    public List<ClosingDailyHealthService.DailyHealth> dailyHealth(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return health.list(from, to);
    }
}
