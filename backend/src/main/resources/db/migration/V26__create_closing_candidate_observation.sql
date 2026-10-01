CREATE TABLE closing_candidate_observation (
    id bigserial PRIMARY KEY,
    run_id bigint NOT NULL REFERENCES closing_recommendation_run(id) ON DELETE CASCADE,
    stock_id bigint NOT NULL REFERENCES stock(id),
    candidate_source varchar(20) NOT NULL,
    disposition varchar(20) NOT NULL,
    decision_reason varchar(80) NOT NULL,
    signal_observed_at timestamp with time zone NOT NULL,
    signal_price numeric(20,4),
    final_score numeric(8,3),
    expected_session_date date NOT NULL,
    status varchar(20) NOT NULL,
    entry_at timestamp with time zone,
    entry_price numeric(20,4),
    exit_at timestamp with time zone,
    exit_price numeric(20,4),
    gross_return_rate numeric(12,6),
    net_return_rate numeric(12,6),
    exit_reason varchar(40),
    execution_ambiguous boolean NOT NULL DEFAULT false,
    target_rate numeric(12,6) NOT NULL,
    stop_rate numeric(12,6) NOT NULL,
    missing_intervals text NOT NULL DEFAULT '[]',
    cost_assumption text NOT NULL DEFAULT '{}',
    cost_status varchar(40) NOT NULL DEFAULT 'NOT_EVALUATED',
    evaluated_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uk_closing_candidate_observation UNIQUE (run_id, stock_id, candidate_source)
);

CREATE INDEX idx_closing_candidate_observation_run
    ON closing_candidate_observation(run_id, disposition, id);
