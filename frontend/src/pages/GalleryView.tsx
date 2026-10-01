import React, { useState, useMemo } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { ViewFull, GalleryConfig } from '@/types/view';

interface GroupedRow { _groupKey: string; _count: number; _children: any[]; }

/** 画廊视图 — 卡片式展示记录 + R2 T2 group by */
export function GalleryViewPage() {
  const { id } = useParams<{ id: string }>();

  const { data: viewData } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });

  const collectionName = viewData?.collection_name;
  const { data: collectionData } = useQuery({
    queryKey: ['collection', collectionName],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collectionName}`),
    enabled: !!collectionName,
  });

  const { data: recordsData } = useQuery({
    queryKey: ['records', collectionName],
    queryFn: () => apiClient.get<any[]>(`/collections/${collectionName}/records?limit=500`),
    enabled: !!collectionName,
  });

  // Move early return after all hooks
  const view = viewData ?? { title: '', collection_name: '', config: {} };
  const collection = collectionData ?? { name: '', fields: [] };
  const fields = collection.fields ?? [];
  const records = recordsData ?? [];
  const fieldMap = new Map(fields.map((f) => [f.name, f]));
  const config = (view.config ?? {}) as unknown as GalleryConfig;
  
  // R2 T2: 分组配置
  const groupByField = config.groupBy;
  const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
  const groupableFields = fields.filter(f => !nonGroupableTypes.includes(f.type));

  // R2 T2: 分组渲染数据
  const groupedData = useMemo(() => {
    if (!groupByField) return null;
    
    const groups = new Map<string, any[]>();
    for (const r of records) {
      const key = String(r[groupByField] ?? '__empty__');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(r);
    }

    const result: GroupedRow[] = [];
    for (const [key, groupRecords] of groups.entries()) {
      result.push({
        _groupKey: key,
        _count: groupRecords.length,
        _children: groupRecords,
      });
    }
    return result;
  }, [groupByField, records]);

  const cardTitleField = config.cardTitleField ?? fields.find((f) => f.type === 'text')?.name ?? 'id';
  const cardFields = config.cardFields ?? fields.filter((f) => f.type !== 'belongsTo' && f.type !== 'hasMany').map((f) => f.name);

  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const toggleGroup = (key: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const expandAll = () => {
    if (groupedData) setExpandedGroups(new Set(groupedData.map(g => g._groupKey)));
  };

  const collapseAll = () => {
    setExpandedGroups(new Set());
  };

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{viewData?.title}</h1>
      
      {groupByField && (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, fontSize: 12 }}>
          <span>分组:</span>
          <select
            value={groupByField}
            onChange={(e) => {
              const newConfig: GalleryConfig = { ...config, groupBy: e.target.value };
              apiClient.put(`/views/${id}`, { config: newConfig });
            }}
            style={{ padding: '4px 8px', borderRadius: 4, background: 'var(--color-bg-secondary)', color: 'var(--color-text-primary)', border: '1px solid var(--color-border-light)' }}
          >
            <option value="">无分组</option>
            {groupableFields.map(f => (
              <option key={f.name} value={f.name}>{f.label ?? f.name}</option>
            ))}
          </select>
          <button onClick={expandAll} className="glass-button" style={{ padding: '2px 8px', fontSize: 11 }}>全部展开</button>
          <button onClick={collapseAll} className="glass-button" style={{ padding: '2px 8px', fontSize: 11 }}>全部折叠</button>
        </div>
      )}

      <p style={{ color: 'var(--color-text-muted)', marginBottom: 24 }}>
        共 {(groupByField ? (groupedData?.reduce((a, g) => a + g._count, 0) ?? 0) : records.length)} 条记录{(groupByField ? ` (${groupedData?.length ?? 0} 组)` : '')}
      </p>

      {!groupByField ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 16 }}>
          {records.map((record) => (
            <Card key={record.id} record={record} fieldMap={fieldMap} titleField={cardTitleField} displayFields={cardFields} />
          ))}
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {groupedData?.map((group) => (
            <div key={group._groupKey}>
              <div
                style={{
                  padding: 12,
                  background: 'rgba(99, 102, 241, 0.1)',
                  borderRadius: 8,
                  marginBottom: 8,
                  cursor: 'pointer',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
                onClick={() => toggleGroup(group._groupKey)}
              >
                <span style={{ fontWeight: 600, color: 'var(--color-text-primary)' }}>
                  {expandedGroups.has(group._groupKey) ? '▼' : '▶'} {group._groupKey} ({group._count} 条)
                </span>
              </div>
              {expandedGroups.has(group._groupKey) && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 16, paddingLeft: 16 }}>
                  {group._children.map((record) => (
                    <Card key={record.id} record={record} fieldMap={fieldMap} titleField={cardTitleField} displayFields={cardFields} />
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {(!groupByField ? records : groupedData?.flatMap(g => g._children) ?? []).length === 0 && (
        <div style={{ textAlign: 'center', padding: 48, color: 'var(--color-text-muted)' }}>
          无数据
        </div>
      )}
    </div>
  );
}

interface CardProps {
  record: any;
  fieldMap: Map<string, FieldDef>;
  titleField: string;
  displayFields: string[];
}

function Card({ record, fieldMap, titleField, displayFields }: CardProps) {
  const title = record[titleField];

  return (
    <div
      className="glass-card"
      style={{ borderRadius: 8, padding: 16 }}
    >
      <h3 style={{ margin: '0 0 12px', fontSize: 16, fontWeight: 600, color: 'var(--color-text-primary)' }}>{title ?? '—'}</h3>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {displayFields.map((fieldName) => {
          const field = fieldMap.get(fieldName);
          const value = record[fieldName];
          if (!field || value == null) return null;
          return (
            <div key={fieldName} style={{ fontSize: 13, color: 'var(--color-text-secondary)' }}>
              <span style={{ color: 'var(--color-text-muted)' }}>{field.label ?? fieldName}: </span>
              <span>{renderValue(value, field)}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function renderValue(value: any, field: FieldDef): React.ReactNode {
  if (field.type === 'boolean') return value === 'true' || value === true ? '✓' : '✗';
  if (field.type === 'date') return String(value).slice(0, 10);
  if (field.type === 'datetime') return String(value).slice(0, 16).replace('T', ' ');
  if (field.type === 'number') return Number(value).toLocaleString();
  return String(value);
}