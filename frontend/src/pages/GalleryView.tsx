import React from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { ViewFull } from '@/types/view';

/** 画廊视图 — 卡片式展示记录 */
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

  if (!viewData || !collectionData) return <p>加载中…</p>;

  const fields = collectionData.fields ?? [];
  const records = recordsData ?? [];
  const fieldMap = new Map(fields.map((f) => [f.name, f]));
  const config = (viewData.config ?? {}) as { cardTitleField?: string; cardFields?: string[] };
  const cardTitleField = config.cardTitleField ?? fields.find((f) => f.type === 'text')?.name ?? 'id';
  const cardFields = config.cardFields ?? fields.filter((f) => f.type !== 'belongsTo' && f.type !== 'hasMany').map((f) => f.name);

  return (
    <div style={{ maxWidth: 1400, margin: '0 auto', background: 'var(--color-bg-primary)', minHeight: '100vh', padding: '24px 0' }}>
      <h1 style={{ color: 'var(--color-text-primary)', margin: '16px 0' }}>{viewData.title}</h1>
      <p style={{ color: 'var(--color-text-muted)', marginBottom: 24 }}>
        共 {records.length} 条记录
      </p>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 16 }}>
        {records.map((record) => (
          <Card key={record.id} record={record} fieldMap={fieldMap} titleField={cardTitleField} displayFields={cardFields} />
        ))}
      </div>

      {records.length === 0 && (
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
