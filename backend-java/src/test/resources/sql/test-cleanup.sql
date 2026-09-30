-- Cleanup test data

DELETE FROM im_message WHERE tenant_id = 'test-tenant';
DELETE FROM external_message_log WHERE tenant_id = 'test-tenant';
DELETE FROM im_channel_member WHERE channel_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM im_channel WHERE id = '11111111-1111-1111-1111-111111111111';
DELETE FROM sys_user WHERE id = '22222222-2222-2222-2222-222222222222';
