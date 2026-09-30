-- Setup test data for InboundMessageServiceIntegrationTest

-- Insert test channel
INSERT INTO im_channel (id, tenant_id, name, channel_type, created_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'test-tenant', 'General', 'private', NOW());

-- Insert test user (already exists in seed data, but ensure it's there)
-- Assuming user 00000000-0000-0000-0000-000000000001 exists from seed data

-- Add user as channel member
INSERT INTO im_channel_member (id, channel_id, user_id, joined_at)
VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', NOW());

-- Insert non-member user for testing
INSERT INTO sys_user (id, username, email, display_name, phone, is_active, created_at)
VALUES ('22222222-2222-2222-2222-222222222222', 'nonmember', 'nonmember@test.com', 'Non Member', NULL, true, NOW());
