package com.sunmo.stockplatform.trade.api;

import com.sunmo.stockplatform.trade.application.TradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/trades")
public class TradeController {
    private final TradeService service;

    public TradeController(TradeService service) {
        this.service = service;
    }

    @GetMapping
    public List<TradeResponse> list(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeResponse create(@Valid @RequestBody TradeRequests.Create body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.create(body, key);
    }

    @GetMapping("/{id}")
    public TradeResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public TradeResponse update(@PathVariable long id, @Valid @RequestBody TradeRequests.Update body) {
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PutMapping("/{id}/journal")
    public TradeResponse journal(@PathVariable long id, @Valid @RequestBody TradeRequests.Journal body) {
        return service.putJournal(id, body);
    }
}
