-- V44: 回填 im_message.mentions 为结构化格式 [{displayName, userId}]
--
-- 背景（为什么要回填）：
--   V42 引入 mentions 列时的落库格式是**纯 userId 字符串数组**：
--       ["00000000-0000-0000-0000-000000000001"]
--   后来 @提及协议统一为**结构化对象数组**（前后端对齐）：
--       [{"displayName":"Admin","userId":"00000000-0000-0000-0000-000000000001"}]
--   旧格式在 ImMessageEntity#getMentionsParsed() 反序列化为 MentionDto 时必然失败
--   （no String-argument constructor... from String value）→ 降级为空列表
--   → **历史消息的 @提及不显示**。
--
-- 本迁移把残留的旧格式回填为新格式：
--   - displayName 取自 users.display_name；查不到用户时回退为 userId（不丢信息）
--   - 幂等：已是对象数组 / 空数组 / NULL 的行不满足条件，重复执行无副作用
--   - 事务内可执行：**不得**使用 CREATE INDEX CONCURRENTLY 这类非事务语句
--     （Flyway 会在事务中运行迁移，混用会直接失败）
--
-- 注意：不要在此文件里建索引；im_message 目前无 tsvector 全文索引（见 V43 已删除的决策）。

UPDATE im_message m
SET mentions = (
    SELECT jsonb_agg(
               jsonb_build_object(
                   'displayName', COALESCE(u.display_name, elem),
                   'userId',      elem
               )
           )::text
    FROM jsonb_array_elements_text(m.mentions::jsonb) AS elem
    LEFT JOIN users u ON u.id::text = elem
)
WHERE m.mentions IS NOT NULL
  AND btrim(m.mentions) <> ''
  -- 只处理「数组且首元素是字符串」的旧格式；新格式首元素是 object，故天然幂等
  AND m.mentions LIKE '[%'
  AND jsonb_typeof(m.mentions::jsonb) = 'array'
  AND jsonb_typeof((m.mentions::jsonb) -> 0) = 'string';
