ALTER TABLE scanner_detection ADD COLUMN opportunity_score numeric(6,3);
ALTER TABLE scanner_detection ADD COLUMN risk_score numeric(6,3);
ALTER TABLE scanner_detection ADD COLUMN score_breakdown text;
ALTER TABLE scanner_detection ADD COLUMN score_version varchar(30);

CREATE INDEX idx_detection_opportunity_score ON scanner_detection(opportunity_score DESC);
CREATE INDEX idx_detection_risk_score ON scanner_detection(risk_score DESC);
