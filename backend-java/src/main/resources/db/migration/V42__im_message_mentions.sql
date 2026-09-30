-- V42: Add mentions column to im_message for storing parsed @mentions
-- Format: JSON array of user IDs, e.g., ["uuid1","uuid2"]
ALTER TABLE im_message ADD COLUMN IF NOT EXISTS mentions TEXT;
