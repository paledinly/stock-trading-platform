/*
 * 관심종목 API가 받는 요청 본문 형식들을 한곳에 모아 둔 파일이에요.
 * 각 형식에 붙은 검증 규칙(@NotBlank, @Size 등)으로 잘못된 입력을 컨트롤러 입구에서 걸러요.
 * WatchlistController가 사용해요.
 */
package com.sunmo.stockplatform.watchlist.api;

import jakarta.validation.constraints.*;

/**
 * 관심종목 요청 형식들을 담는 묶음 클래스예요(직접 객체로 만들지 않아요).
 * 관공서의 신청서 양식 서랍처럼, 그룹 만들기·수정, 종목 추가·이동, 실시간 고정 신청서가 각각 들어 있어요.
 * version 값은 화면이 마지막으로 본 버전이에요. 그 사이 누가 바꿨으면 서버가 충돌(409)로 거절해요.
 */
public final class WatchlistRequests {
    /** 객체 생성을 막는 비공개 생성자예요. */
    private WatchlistRequests() {
    }

    /** 그룹 만들기 요청이에요. 이름(1~80자, 공백만은 안 됨)과 표시 순서(선택, 0 이상)를 받아요. */
    public record CreateGroup(@NotBlank @Size(max = 80) String name, @PositiveOrZero Integer displayOrder) {
    }

    /** 그룹 수정 요청이에요. 이름과 순서는 바꿀 것만 보내고(null이면 그대로), version은 꼭 받아요. */
    public record UpdateGroup(@Size(min = 1, max = 80) String name, @PositiveOrZero Integer displayOrder,
            @PositiveOrZero long version) {
    }

    /** 종목 추가 요청이에요. 그룹 id, 종목 코드(대문자/숫자 6~12자리), 순서(선택)를 받아요. */
    public record AddItem(@NotNull @Positive Long groupId,
            @NotBlank @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode, @PositiveOrZero Integer displayOrder) {
    }

    /** 항목 이동 요청이에요. 대상 그룹 id, 새 순서, version을 모두 받아요. */
    public record MoveItem(@NotNull @Positive Long groupId, @NotNull @PositiveOrZero Integer displayOrder,
            @PositiveOrZero long version) {
    }

    /** 실시간 고정 켜기/끄기 요청이에요. enabled가 true면 켜고, false면 꺼요. */
    public record UpdateRealtime(boolean enabled, @PositiveOrZero long version) {
    }
}
