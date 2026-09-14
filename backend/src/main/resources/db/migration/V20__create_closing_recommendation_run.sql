CREATE TABLE closing_recommendation_run (
    id bigserial PRIMARY KEY,
    recommendation_date date NOT NULL,
    generated_at timestamp with time zone NOT NULL,
    strategy_version varchar(40) NOT NULL,
    response_snapshot text NOT NULL
);
CREATE INDEX idx_closing_run_date_generated ON closing_recommendation_run(recommendation_date, generated_at DESC);
