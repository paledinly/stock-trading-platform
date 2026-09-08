CREATE TABLE precision_subscription_session (
 id bigserial PRIMARY KEY,
 session_date date NOT NULL,
 stock_code varchar(12) NOT NULL,
 requested_at timestamp with time zone NOT NULL,
 activated_at timestamp with time zone,
 ended_at timestamp with time zone,
 status varchar(20) NOT NULL,
 end_reason varchar(40),
 created_at timestamp with time zone NOT NULL DEFAULT now(),
 updated_at timestamp with time zone NOT NULL DEFAULT now(),
 CONSTRAINT ck_precision_subscription_status CHECK(status IN ('REQUESTED','ACTIVE','ENDED','REJECTED'))
);

CREATE INDEX idx_precision_subscription_date ON precision_subscription_session(session_date, requested_at);
CREATE INDEX idx_precision_subscription_active ON precision_subscription_session(stock_code, status);
