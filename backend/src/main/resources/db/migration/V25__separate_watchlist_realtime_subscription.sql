ALTER TABLE watchlist_item
    ADD COLUMN realtime_pinned boolean NOT NULL DEFAULT false;

CREATE INDEX idx_watchlist_item_realtime_pinned
    ON watchlist_item (realtime_pinned, stock_id);
