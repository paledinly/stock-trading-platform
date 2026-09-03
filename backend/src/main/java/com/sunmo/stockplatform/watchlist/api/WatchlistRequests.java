package com.sunmo.stockplatform.watchlist.api;

import jakarta.validation.constraints.*;

public final class WatchlistRequests {
    private WatchlistRequests() {
    }

    public record CreateGroup(@NotBlank @Size(max = 80) String name, @PositiveOrZero Integer displayOrder) {
    }

    public record UpdateGroup(@Size(min = 1, max = 80) String name, @PositiveOrZero Integer displayOrder,
            @PositiveOrZero long version) {
    }

    public record AddItem(@NotNull @Positive Long groupId,
            @NotBlank @Pattern(regexp = "[A-Z0-9]{6,12}") String stockCode, @PositiveOrZero Integer displayOrder) {
    }

    public record MoveItem(@NotNull @Positive Long groupId, @NotNull @PositiveOrZero Integer displayOrder,
            @PositiveOrZero long version) {
    }
}
