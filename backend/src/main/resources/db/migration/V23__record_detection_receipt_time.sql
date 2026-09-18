ALTER TABLE scanner_detection ADD COLUMN received_at timestamp with time zone;

-- Existing rows have no trustworthy receipt timestamp; leave them null.
CREATE INDEX idx_scanner_detection_received_at ON scanner_detection (received_at);
