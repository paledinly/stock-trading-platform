CREATE TABLE intraday_input (
    id bigserial PRIMARY KEY,
    session_date date NOT NULL,
    stock_code varchar(12) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    evaluated_at timestamp with time zone NOT NULL,
    payload text NOT NULL
);
CREATE INDEX idx_intraday_input_session ON intraday_input(session_date, id);
CREATE TABLE intraday_recommendation (
    id uuid PRIMARY KEY,
    session_date date NOT NULL,
    stock_code varchar(12) NOT NULL,
    setup varchar(24) NOT NULL,
    recommended_at timestamp with time zone NOT NULL,
    snapshot text NOT NULL,
    outcome text NOT NULL,
    tracking_complete boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_intraday_signal UNIQUE(stock_code, setup, recommended_at)
);
CREATE INDEX idx_intraday_recommendation_date ON intraday_recommendation(session_date, recommended_at);
CREATE INDEX idx_intraday_recommendation_pending ON intraday_recommendation(tracking_complete);
