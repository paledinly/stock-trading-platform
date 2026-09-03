package com.sunmo.stockplatform.watchlist.api;

import com.sunmo.stockplatform.watchlist.application.WatchlistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WatchlistController {
    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping("/watchlists")
    public WatchlistResponse get() {
        return service.getWatchlists();
    }

    @PostMapping("/watchlist-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse.Group createGroup(@Valid @RequestBody WatchlistRequests.CreateGroup body) {
        return service.createGroup(body.name(), body.displayOrder());
    }

    @PatchMapping("/watchlist-groups/{id}")
    public WatchlistResponse.Group updateGroup(@PathVariable long id,
            @Valid @RequestBody WatchlistRequests.UpdateGroup body) {
        return service.updateGroup(id, body.name(), body.displayOrder(), body.version());
    }

    @DeleteMapping("/watchlist-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable long id) {
        service.deleteGroup(id);
    }

    @PostMapping("/watchlists")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse.Item add(@Valid @RequestBody WatchlistRequests.AddItem body) {
        return service.addItem(body.groupId(), body.stockCode(), body.displayOrder());
    }

    @PatchMapping("/watchlists/{id}")
    public WatchlistResponse.Item move(@PathVariable long id, @Valid @RequestBody WatchlistRequests.MoveItem body) {
        return service.moveItem(id, body.groupId(), body.displayOrder(), body.version());
    }

    @DeleteMapping("/watchlists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.deleteItem(id);
    }
}
