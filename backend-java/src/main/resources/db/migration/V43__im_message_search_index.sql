-- Phase 61 T2: IM message search index with tsvector
-- Adds full-text search capability using PostgreSQL's native tsvector/tsquery

-- Step 1: Add tsvector column
ALTER TABLE im_message ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- Step 2: Create GIN index for full-text search
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_im_message_content_tsv 
ON im_message USING GIN (content_tsv);

-- Step 3: Create trigger function to auto-update tsvector
CREATE OR REPLACE FUNCTION update_im_message_content_tsv()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.content IS DISTINCT FROM OLD.content) THEN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Step 4: Create trigger to auto-maintain tsvector on INSERT/UPDATE
DROP TRIGGER IF EXISTS trg_im_message_update_content_tsv ON im_message;
CREATE TRIGGER trg_im_message_update_content_tsv
  BEFORE INSERT OR UPDATE OF content ON im_message
  FOR EACH ROW
  EXECUTE FUNCTION update_im_message_content_tsv();

-- Step 5: Backfill existing data
UPDATE im_message 
SET content_tsv = to_tsvector('simple', COALESCE(content, ''))
WHERE content_tsv IS NULL;

-- Note: For production, run this in a transaction with proper locking considerations
-- The CONCURRENTLY keyword in CREATE INDEX allows the index creation without blocking writes
