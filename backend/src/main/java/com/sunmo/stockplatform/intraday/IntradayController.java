package com.sunmo.stockplatform.intraday;

import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/intraday")
public class IntradayController {
    private final IntradayService service;
    public IntradayController(IntradayService service) { this.service = service; }
    @GetMapping("/status") public Map<String, Object> status() { return service.status(); }
    @GetMapping("/recommendations") public List<IntradayModel.Result> recommendations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) { return service.list(date); }
    @GetMapping("/analytics") public Object analytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return IntradayAnalytics.analyze(service.list(date));
    }
    @PostMapping("/replay") public Object replay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody(required = false) IntradayProperties experiment) { return service.replay(date, experiment); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(IllegalArgumentException error) {
        return Map.of("detail", "Invalid intraday date or experiment configuration");
    }
}
