/**
 * 字段类型契约测试 — PHASE71 方案 B
 * 断言前端 FIELD_TYPES ⊆ 后端 AsyncMigrationService.mapJsonbType 支持的类型
 */
import { describe, it, expect } from 'vitest';

/** 后端支持的字段类型 (来自 AsyncMigrationService.java:151-160) */
const BACKEND_FIELD_TYPES = new Set([
  // TEXT
  'text', 'select', 'multiSelect', 'email', 'url', 'phone',
  // NUMERIC
  'number', 'currency', 'percent',
  // BOOLEAN
  'boolean',
  // TIMESTAMPTZ
  'date', 'datetime', 'createdTime', 'lastModifiedTime',
  // TEXT (attachment)
  'attachment',
  // UUID
  'belongsTo', 'hasMany', 'createdBy', 'lastModifiedBy',
  // TEXT (formula)
  'formula',
  // INTEGER
  'duration', 'rating',
  // BIGINT
  'autonumber',
  // 派生字段 (不落物理列，但 isValidType 允许)
  'rollup', 'lookup',
]);

/** 前端可选字段类型 (来自 SchemaDesigner.tsx) */
const FRONTEND_FIELD_TYPES = [
  'text', 'email', 'url', 'phone',
  'number', 'currency', 'percent', 'duration', 'rating', 'autonumber',
  'boolean',
  'date', 'datetime', 'createdTime', 'lastModifiedTime',
  'select', 'multiSelect',
  'attachment',
  'belongsTo', 'hasMany', 'createdBy', 'lastModifiedBy',
  'formula', 'rollup', 'lookup',
];

describe('Field Types Contract', () => {
  it('前端可选类型必须是后端支持类型的子集', () => {
    const missingInBackend = FRONTEND_FIELD_TYPES.filter(
      (t) => !BACKEND_FIELD_TYPES.has(t)
    );
    
    expect(missingInBackend).toEqual([]);
  });

  it('前端应覆盖所有后端基础类型 (除系统自动字段外)', () => {
    const backendBasics = [
      'text', 'select', 'multiSelect', 'email', 'url', 'phone',
      'number', 'currency', 'percent',
      'boolean',
      'date', 'datetime',
      'attachment',
      'belongsTo', 'hasMany',
      'formula',
      'duration', 'rating',
      'autonumber',
    ];
    
    const missingInFrontend = backendBasics.filter(
      (t) => !FRONTEND_FIELD_TYPES.includes(t)
    );
    
    expect(missingInFrontend).toEqual([]);
  });

  it('派生字段 (rollup/lookup) 应在前端可用', () => {
    expect(FRONTEND_FIELD_TYPES).toContain('rollup');
    expect(FRONTEND_FIELD_TYPES).toContain('lookup');
  });

  it('系统自动字段应在前端可用', () => {
    expect(FRONTEND_FIELD_TYPES).toContain('createdTime');
    expect(FRONTEND_FIELD_TYPES).toContain('lastModifiedTime');
    expect(FRONTEND_FIELD_TYPES).toContain('createdBy');
    expect(FRONTEND_FIELD_TYPES).toContain('lastModifiedBy');
  });
});
