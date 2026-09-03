ALTER TABLE scanner_setting DROP CONSTRAINT ck_scanner_setting_type;

ALTER TABLE scanner_setting
    ADD CONSTRAINT ck_scanner_setting_type CHECK (
        scanner_type IN (
            'VOLUME',
            'PRICE_RISE',
            'MOMENTUM',
            'VOLUME_BREAKOUT',
            'TURNOVER_BREAKOUT',
            'HIGH_BREAKOUT',
            'VWAP_BREAKOUT',
            'VWAP_RECLAIM',
            'PULLBACK_REBREAK'
        )
    );

ALTER TABLE scanner_detection ADD COLUMN detection_reason text;
