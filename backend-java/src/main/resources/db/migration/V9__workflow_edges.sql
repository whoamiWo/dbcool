-- V9: 给 workflows 加 edges 列(Week 14 图遍历)
ALTER TABLE workflows ADD COLUMN edges JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN workflows.edges IS '工作流边数组 [{id, source, target, sourceHandle?}];节点间的连接关系';
