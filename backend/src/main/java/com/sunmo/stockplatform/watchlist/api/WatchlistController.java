/*
 * 관심종목 그룹과 항목을 조회·추가·수정·삭제하는 REST API 입구예요.
 * 실시간 고정(realtime pin) 켜기/끄기도 여기서 받아요. 실제 처리는 WatchlistService가 해요.
 * Web/Mobile의 관심종목 화면이 호출해요.
 */
package com.sunmo.stockplatform.watchlist.api;

import com.sunmo.stockplatform.watchlist.application.WatchlistService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 관심종목 관련 HTTP 요청을 받는 컨트롤러예요.
 * 도서관 대출 창구처럼 요청서(요청 본문)의 형식을 확인한 뒤, 실제 책장 정리는 안쪽 사서(WatchlistService)에게 맡겨요.
 * REST(주소와 HTTP 메서드로 데이터를 주고받는 웹 API 방식) 규칙에 따라 GET/POST/PATCH/DELETE로 나눠 받아요.
 */
@RestController
@RequestMapping("/api/v1")
public class WatchlistController {
    private final WatchlistService service;

    /** 의존성 주입(필요한 부품을 스프링이 대신 만들어 넣어 주는 것)으로 WatchlistService를 받아요. */
    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    /** 내 관심종목 그룹과 그 안의 항목 전체를 돌려줘요. 받는 것: 없음. 돌려주는 것: 그룹 목록(항목 포함). */
    @GetMapping("/watchlists")
    public WatchlistResponse get() {
        return service.getWatchlists();
    }

    /** 새 그룹을 만들어요. 받는 것: 이름, 표시 순서(선택). 돌려주는 것: 만든 그룹(201 Created). */
    @PostMapping("/watchlist-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse.Group createGroup(@Valid @RequestBody WatchlistRequests.CreateGroup body) {
        return service.createGroup(body.name(), body.displayOrder());
    }

    /**
     * 그룹 이름이나 순서를 바꿔요.
     * 받는 것: 그룹 id, 바꿀 값, 화면이 알고 있던 version(동시 수정 충돌 확인용). 돌려주는 것: 바뀐 그룹.
     */
    @PatchMapping("/watchlist-groups/{id}")
    public WatchlistResponse.Group updateGroup(@PathVariable long id,
            @Valid @RequestBody WatchlistRequests.UpdateGroup body) {
        return service.updateGroup(id, body.name(), body.displayOrder(), body.version());
    }

    /** 그룹을 지워요(안의 항목도 함께 지워져요). 받는 것: 그룹 id. 돌려주는 것: 없음(204 No Content). */
    @DeleteMapping("/watchlist-groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable long id) {
        service.deleteGroup(id);
    }

    /** 그룹에 종목을 추가해요. 받는 것: 그룹 id, 종목 코드, 순서(선택). 돌려주는 것: 추가된 항목(201 Created). */
    @PostMapping("/watchlists")
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse.Item add(@Valid @RequestBody WatchlistRequests.AddItem body) {
        return service.addItem(body.groupId(), body.stockCode(), body.displayOrder());
    }

    /** 항목을 다른 그룹이나 다른 순서로 옮겨요. 받는 것: 항목 id, 대상 그룹, 순서, version. 돌려주는 것: 옮긴 항목. */
    @PatchMapping("/watchlists/{id}")
    public WatchlistResponse.Item move(@PathVariable long id, @Valid @RequestBody WatchlistRequests.MoveItem body) {
        return service.moveItem(id, body.groupId(), body.displayOrder(), body.version());
    }

    /**
     * 항목의 실시간 고정(KIS WebSocket 실시간 시세 구독 유지)을 켜거나 꺼요.
     * 받는 것: 항목 id, 켤지 여부, version. 돌려주는 것: 바뀐 항목.
     */
    @PatchMapping("/watchlists/{id}/realtime")
    public WatchlistResponse.Item updateRealtime(@PathVariable long id,
            @Valid @RequestBody WatchlistRequests.UpdateRealtime body) {
        return service.updateRealtime(id, body.enabled(), body.version());
    }

    /** 관심종목 항목 하나를 지워요. 받는 것: 항목 id. 돌려주는 것: 없음(204 No Content). */
    @DeleteMapping("/watchlists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.deleteItem(id);
    }
}
