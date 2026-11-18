-- E2 left terminal failures as FAILED with no next attempt; in E3 that state is DEAD.
UPDATE deliveries
   SET status = 'DEAD', updated_at = now()
 WHERE status = 'FAILED' AND next_attempt_at IS NULL;

DROP INDEX idx_deliveries_due;
CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
