/** 视图(Week 9 Epic 3)类型定义. */

export type ViewType = 'table' | 'kanban' | 'detail' | 'gallery' | 'calendar';

export interface TableColumn {
  field: string;
  label?: string;
  width?: number; // px
  visible?: boolean; // 默认 true
}

export interface SortRule {
  field: string;
  direction: 'asc' | 'desc';
}

export interface FilterRule {
  field: string;
  op: 'eq' | 'neq' | 'contains' | 'gt' | 'lt' | 'empty' | 'notEmpty';
  value?: unknown;
}

export interface TableConfig {
  columns: TableColumn[];
  pageSize?: number; // 默认 20
  sort?: SortRule[];
  filters?: FilterRule[];
}

export interface KanbanConfig {
  groupBy: string; // field name
  cardTitleField?: string;
  cardFields?: string[]; // 显示在卡片上的字段
}

export interface DetailConfig {
  fields: string[]; // 字段顺序
  hiddenFields?: string[];
}

export type ViewConfig = TableConfig | KanbanConfig | DetailConfig;

export interface ViewMeta {
  id: string;
  collection_name: string;
  name: string;
  title: string;
  type: ViewType;
  config_json: string;
  shared_with_json: string;
  tenant_id: string;
  created_at: string;
  updated_at: string | null;
  created_by: string | null;
}

export interface ViewFull extends ViewMeta {
  config: Record<string, unknown>;
}

export interface CreateViewRequest {
  collectionName: string;
  name: string;
  title?: string;
  type: ViewType;
  config?: string; // JSON 字符串
  sharedWith?: string;
}
