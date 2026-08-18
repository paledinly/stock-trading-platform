CREATE TABLE watchlist_group (
    id bigserial PRIMARY KEY,
    owner_id bigint NOT NULL,
    name varchar(80) NOT NULL,
    display_order integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_watchlist_group_owner_name UNIQUE (owner_id, name),
    CONSTRAINT ck_watchlist_group_display_order CHECK (display_order >= 0)
);

CREATE TABLE watchlist_item (
    id bigserial PRIMARY KEY,
    group_id bigint NOT NULL REFERENCES watchlist_group(id) ON DELETE CASCADE,
    stock_id bigint NOT NULL REFERENCES stock(id),
    display_order integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_watchlist_item_group_stock UNIQUE (group_id, stock_id),
    CONSTRAINT ck_watchlist_item_display_order CHECK (display_order >= 0)
);

CREATE INDEX idx_watchlist_group_owner_order ON watchlist_group (owner_id, display_order, id);
CREATE INDEX idx_watchlist_item_group_order ON watchlist_item (group_id, display_order, id);
