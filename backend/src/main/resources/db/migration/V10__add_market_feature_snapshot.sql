ALTER TABLE scanner_detection ADD COLUMN feature_snapshot text;
ALTER TABLE scanner_detection ADD COLUMN feature_version varchar(30);

CREATE INDEX idx_detection_feature_version ON scanner_detection(feature_version);
