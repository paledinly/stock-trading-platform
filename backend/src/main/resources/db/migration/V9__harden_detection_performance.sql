ALTER TABLE detection_performance ADD COLUMN mfe numeric(12,6);
ALTER TABLE detection_performance ADD COLUMN mae numeric(12,6);
ALTER TABLE detection_performance ADD COLUMN recovery_used boolean NOT NULL DEFAULT false;
ALTER TABLE detection_performance ADD COLUMN finalization_reason varchar(30);

UPDATE detection_performance
SET mfe = max_return,
    mae = max_drawdown
WHERE max_return IS NOT NULL OR max_drawdown IS NOT NULL;

UPDATE detection_performance
SET calculation_version = 'performance-v2'
WHERE status = 'PENDING';
