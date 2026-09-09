/** Form(表单设计器) 类型定义 — Epic 2. */

import type { FieldDef, FieldType } from './collection';

export interface FormLayoutItem {
  field: string; // FieldDef.name
  span?: number; // 12 / 24,默认 24
}

export type VisibilityOperator = 'eq' | 'neq' | 'in' | 'notIn' | 'empty' | 'notEmpty' | 'gt' | 'lt';

export interface VisibilityRule {
  when: string;       // 触发显隐的字段名
  op: VisibilityOperator;
  value?: unknown;
}

export type ValidationType =
  | 'required'
  | 'minLength'
  | 'maxLength'
  | 'min'
  | 'max'
  | 'pattern'
  | 'email';

export interface ValidationRule {
  type: ValidationType;
  value?: number | string;
  message?: string;
}

export type SubmitAction = 'redirect' | 'stay' | 'workflow';

export interface SubmitRule {
  action: SubmitAction;
  url?: string;       // action=redirect 时
  workflowId?: string; // action=workflow 时
  message?: string;   // action=stay 时弹的提示
}

export interface FormRules {
  visibility?: Record<string, VisibilityRule>; // field name → 规则
  validation?: Record<string, ValidationRule[]>; // field name → 规则数组
  submit?: SubmitRule;
}

export interface FormMeta {
  id: string;
  collection_name: string;
  title: string;
  description: string;
  layout_json: string;
  rules_json: string;
  tenant_id: string;
  created_at: string;
  updated_at: string | null;
}

export interface FormFull extends FormMeta {
  layout: FormLayoutItem[];
  rules: FormRules;
}

export interface CreateFormRequest {
  collectionName: string;
  title: string;
  description?: string;
  layout?: string; // JSON 字符串
  rules?: string;  // JSON 字符串
}

export type { FieldDef, FieldType };
