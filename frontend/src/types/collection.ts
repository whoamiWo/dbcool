/** Collection / Field 类型定义 — 与后端一致. */

export type FieldType =
  | 'text'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime' // Week 41 D1.1: 区别于 date,精确到分钟(后端共用 TIMESTAMPTZ)
  | 'select'
  | 'multiSelect'
  | 'attachment' // Week 41 D1.2: 文件,Week 42+ 接 MinIO
  | 'belongsTo'
  | 'hasMany'
  | 'formula'
  | 'rollup'
  | 'lookup';

export interface FieldDef {
  name: string;
  type: FieldType;
  required: boolean;
  label?: string;
  options?: Record<string, unknown>;
  primaryKey?: boolean;
  unique?: boolean;
  defaultValue?: string;
}

export interface CollectionMeta {
  id: string;
  name: string;
  title: string;
  description: string;
  fields_json?: string;
  fields?: FieldDef[];
  tenant_id: string;
  created_at: string;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface LoginResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  user: {
    id: string;
    username: string;
    display_name: string;
    tenant_id: string;
    roles: string[];
  };
  issued_at: string;
}

export interface MutationResponse {
  async: boolean;
  operation: string;
  target: string;
  job_id?: string;
  poll_url?: string;
}

export interface MigrationJob {
  id: string;
  collection: string;
  operation: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  error: string;
  created_at: string;
  started_at: string | null;
  finished_at: string | null;
}
