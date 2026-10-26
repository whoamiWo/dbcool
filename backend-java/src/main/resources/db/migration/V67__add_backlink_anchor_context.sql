-- Add anchor_text and context columns to wiki_backlink table
-- Supports [[slug|anchor]] syntax and stores context excerpt around link mentions

ALTER TABLE wiki_backlink
ADD COLUMN anchor_text VARCHAR(512),
ADD COLUMN context VARCHAR(1024);
