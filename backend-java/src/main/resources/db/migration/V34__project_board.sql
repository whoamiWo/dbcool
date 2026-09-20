-- V34: Trello 式看板 4 张表（board_lists / card_checklists / card_checklist_items / card_labels）
-- 修复 ddl-auto: validate 因缺表导致 Spring 启动失败的问题

CREATE TABLE IF NOT EXISTS board_lists (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    project_id UUID NOT NULL,
    title VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'TODO',
    sort_order INTEGER NOT NULL DEFAULT 0,
    wip_limit INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_board_lists_tenant_project ON board_lists (tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_board_lists_project ON board_lists (project_id);

CREATE TABLE IF NOT EXISTS card_checklists (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_id UUID NOT NULL,
    title VARCHAR(128) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_card_checklists_tenant_task ON card_checklists (tenant_id, task_id);
CREATE INDEX IF NOT EXISTS idx_card_checklists_task ON card_checklists (task_id);

CREATE TABLE IF NOT EXISTS card_checklist_items (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    checklist_id UUID NOT NULL,
    task_id UUID NOT NULL,
    title VARCHAR(256) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_card_checklist_items_checklist ON card_checklist_items (checklist_id);
CREATE INDEX IF NOT EXISTS idx_card_checklist_items_task ON card_checklist_items (task_id);
CREATE INDEX IF NOT EXISTS idx_card_checklist_items_tenant ON card_checklist_items (tenant_id);

CREATE TABLE IF NOT EXISTS card_labels (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    project_id UUID NOT NULL,
    name VARCHAR(64) NOT NULL,
    color VARCHAR(16) NOT NULL DEFAULT 'BLUE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_card_labels_tenant_project ON card_labels (tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_card_labels_project ON card_labels (project_id);
