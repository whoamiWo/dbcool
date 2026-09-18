-- 项目管理模块:任务表(Week 44 — 对标 Trello 看板 + 甘特图)
-- 平台表统一落在 public schema,通过 tenant_id 列做租户隔离(与 V20__wiki.sql 一致)

CREATE TABLE project_tasks (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    project_id   UUID NOT NULL,
    parent_id    UUID REFERENCES project_tasks(id) ON DELETE SET NULL,
    title        VARCHAR(256) NOT NULL,
    description  TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'TODO',
    priority     VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    assignee_id  UUID,
    start_date   TIMESTAMP,
    end_date     TIMESTAMP,
    progress     INT NOT NULL DEFAULT 0,
    sort_order   INT NOT NULL DEFAULT 0,
    created_by   UUID,
    updated_by   UUID,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP
);

CREATE INDEX idx_project_tasks_tenant_project ON project_tasks (tenant_id, project_id);
CREATE INDEX idx_project_tasks_parent ON project_tasks (parent_id);
CREATE INDEX idx_project_tasks_assignee ON project_tasks (assignee_id);
CREATE INDEX idx_project_tasks_status ON project_tasks (status);
