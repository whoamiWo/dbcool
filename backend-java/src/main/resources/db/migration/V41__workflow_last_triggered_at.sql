-- V41: Add last_triggered_at column to workflows table for persistent schedule trigger tracking
ALTER TABLE workflows ADD COLUMN last_triggered_at TIMESTAMPTZ;

-- Backfill: set last_triggered_at to created_at for existing rows that have no last_triggered_at
UPDATE workflows SET last_triggered_at = created_at WHERE last_triggered_at IS NULL;

-- Add comment for clarity
COMMENT ON COLUMN workflows.last_triggered_at IS 'Last time the scheduled workflow was triggered (persistent across restarts)';