CREATE TABLE closing_paper_entry (
    run_id bigint NOT NULL REFERENCES closing_recommendation_run(id),
    stock_code varchar(20) NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    deadline timestamp with time zone NOT NULL,
    status varchar(50) NOT NULL,
    source_at timestamp with time zone,
    received_at timestamp with time zone,
    entered_at timestamp with time zone,
    price numeric(20,4),
    PRIMARY KEY (run_id, stock_code)
);
CREATE INDEX idx_closing_paper_entry_pending ON closing_paper_entry(status, deadline);
