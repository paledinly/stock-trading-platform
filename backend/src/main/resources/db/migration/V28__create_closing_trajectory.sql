CREATE TABLE closing_minute_feature (
    symbol varchar(20) NOT NULL,
    start_time timestamp with time zone NOT NULL,
    finalized_at timestamp with time zone NOT NULL,
    payload text NOT NULL,
    PRIMARY KEY (symbol, start_time)
);
CREATE TABLE closing_context_observation (
    kind varchar(30) NOT NULL,
    symbol varchar(20) NOT NULL,
    scope varchar(30) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    payload text NOT NULL,
    PRIMARY KEY (kind, symbol, scope, received_at)
);
CREATE TABLE closing_trajectory_snapshot (
    symbol varchar(20) NOT NULL,
    evaluation_time timestamp with time zone NOT NULL,
    evaluated_at timestamp with time zone NOT NULL,
    payload text NOT NULL,
    outcome text,
    PRIMARY KEY (symbol, evaluation_time)
);
CREATE INDEX ix_closing_trajectory_evaluation ON closing_trajectory_snapshot(evaluation_time, symbol);
CREATE INDEX ix_closing_minute_time ON closing_minute_feature(start_time);
CREATE INDEX ix_closing_context_time ON closing_context_observation(received_at);
