import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import type { CollectionMeta, FieldDef } from '@/types/collection';
import type { CreateViewRequest, TableColumn, ViewFull, ViewType } from '@/types/view';

/** 视图设计器(Week 9 + Week 15 columns UI) — 创建/编辑视图 */
export function ViewDesignerPage() {
  const { collection, id } = useParams<{ collection: string; id?: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [name, setName] = useState('new_view');
  const [title, setTitle] = useState('新视图');
  const [type, setType] = useState<ViewType>('table');
  const [groupBy, setGroupBy] = useState<string>('');
  const [pageSize, setPageSize] = useState(20);
  const [columns, setColumns] = useState<TableColumn[]>([]);
  const [detailFields, setDetailFields] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const { data: collectionData } = useQuery({
    queryKey: ['collection', collection],
    queryFn: () => apiClient.get<CollectionMeta>(`/collections/${collection}`),
    enabled: !!collection,
  });
  const fields: FieldDef[] = collectionData?.fields ?? [];

  const { data: viewData } = useQuery({
    queryKey: ['view', id],
    queryFn: () => apiClient.get<ViewFull>(`/views/${id}`),
    enabled: !!id,
  });

  // 默认列:从 collection 字段全选 + 保留字段顺序
  const defaultColumns = useMemo<TableColumn[]>(
    () => fields.map((f) => ({ field: f.name, label: f.label ?? f.name, width: 160, visible: true })),
    [fields]
  );

  useEffect(() => {
    if (viewData) {
      const v = viewData;
      setName(v.name);
      setTitle(v.title);
      setType(v.type);
      const cfg = (v.config ?? {}) as Record<string, unknown>;
      if (typeof cfg.pageSize === 'number') setPageSize(cfg.pageSize);
      if (typeof cfg.groupBy === 'string') setGroupBy(cfg.groupBy);
      // 恢复 columns(没有就用 default)
      if (Array.isArray(cfg.columns)) {
        setColumns(cfg.columns as TableColumn[]);
      } else if (v.type === 'table') {
        setColumns(defaultColumns);
      }
      // 详情视图字段顺序
      if (Array.isArray(cfg.fields)) {
        setDetailFields(cfg.fields as string[]);
      } else if (v.type === 'detail') {
        setDetailFields(fields.map((f) => f.name));
      }
    }
  }, [viewData, defaultColumns, fields]);

  // 新建/字段集变化时同步默认列
  useEffect(() => {
    if (!id && type === 'table' && columns.length === 0 && fields.length > 0) {
      setColumns(defaultColumns);
    }
    if (!id && type === 'detail' && detailFields.length === 0 && fields.length > 0) {
      setDetailFields(fields.map((f) => f.name));
    }
  }, [id, type, columns.length, detailFields.length, fields, defaultColumns]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      const config: Record<string, unknown> = {};
      if (type === 'table') {
        config.pageSize = pageSize;
        config.columns = columns.filter((c) => c.visible !== false);
      }
      if (type === 'kanban') config.groupBy = groupBy;
      if (type === 'detail') config.fields = detailFields;

      const body: CreateViewRequest = {
        collectionName: collection!,
        name,
        title,
        type,
        config: JSON.stringify(config),
      };
      if (id) {
        return apiClient.put<ViewFull>(`/views/${id}`, body);
      }
      return apiClient.post<ViewFull>('/views', body);
    },
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['views', collection] });
      const viewId = id ?? res.id;
      navigate(`/views/${viewId}/run`);
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } };
      setError(e.response?.data?.message ?? '保存失败');
    },
  });

  if (!collection) return <p>缺少 collection 参数</p>;
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) {
    setError('name 必须是小写字母开头的英文/数字/下划线');
  }

  return (
    <div>
      <button
        onClick={() => navigate(`/designer/collections/${collection}`)}
        style={{ background: 'none', border: 'none', color: 'var(--color-text-disabled)', cursor: 'pointer', marginBottom: 16 }}
      >
        ← 返回 {collection}
      </button>
      <h1>{id ? '编辑' : '新建'}视图</h1>

      {error && (
        <div style={{ padding: 8, marginBottom: 12, background: 'rgba(239,68,68,0.2)', color: 'var(--color-error)', borderRadius: 4 }}>
          {error}
        </div>
      )}

      <div
        style={{
          padding: 16,
          background: 'var(--color-text-primary)',
          borderRadius: 8,
          boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
          maxWidth: 600,
        }}
      >
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>技术名称</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ padding: 8, width: '100%', fontFamily: 'monospace' }}
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>显示标题</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            style={{ padding: 8, width: '100%' }}
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <label style={{ display: 'block', fontWeight: 500 }}>类型</label>
          <select value={type} onChange={(e) => setType(e.target.value as ViewType)} style={{ padding: 8, width: '100%' }}>
            <option value="table">表格视图</option>
            <option value="kanban">看板视图</option>
            <option value="detail">详情视图</option>
            <option value="timeline">时间线视图</option>
          </select>
        </div>

        {type === 'kanban' && (
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', fontWeight: 500 }}>按哪个字段分组</label>
            <select value={groupBy} onChange={(e) => setGroupBy(e.target.value)} style={{ padding: 8, width: '100%' }}>
              <option value="">-- 选择字段 --</option>
              {fields.map((f) => (
                <option key={f.name} value={f.name}>
                  {f.label ?? f.name}
                </option>
              ))}
            </select>
          </div>
        )}

        {type === 'table' && (
          <>
            <div style={{ marginBottom: 12 }}>
              <label style={{ display: 'block', fontWeight: 500 }}>每页条数</label>
              <input
                type="number"
                value={pageSize}
                onChange={(e) => setPageSize(Number(e.target.value))}
                style={{ padding: 8, width: '100%' }}
              />
            </div>
            <ColumnEditor columns={columns} fields={fields} onChange={setColumns} />
          </>
        )}

        {type === 'detail' && (
          <DetailFieldEditor fields={fields} selected={detailFields} onChange={setDetailFields} />
        )}

        <div style={{ padding: 8, background: 'var(--color-text-primary)', borderRadius: 4, fontSize: 12, color: 'var(--color-text-disabled)' }}>
          💡 视图运行后可继续调整筛选/排序(US-202/203);列控制在保存前调整
        </div>

        <button
          onClick={() => saveMutation.mutate()}
          disabled={saveMutation.isPending}
          style={{
            marginTop: 16,
            padding: '8px 16px',
            background: saveMutation.isPending ? 'var(--color-text-muted)' : 'var(--color-bg-secondary)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: saveMutation.isPending ? 'not-allowed' : 'pointer',
          }}
        >
          {saveMutation.isPending ? '保存中…' : '保存并打开'}
        </button>
      </div>
    </div>
  );
}

/* === US-206 列设置组件 === */
function ColumnEditor({
  columns, fields, onChange,
}: {
  columns: TableColumn[];
  fields: FieldDef[];
  onChange: (next: TableColumn[]) => void;
}) {
  const visibleCount = columns.filter((c) => c.visible !== false).length;
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={{ display: 'block', fontWeight: 500, marginBottom: 6 }}>
        列设置 ({visibleCount}/{columns.length} 可见)
      </label>
      <div style={{ border: '1px solid var(--color-border-light)', borderRadius: 4, padding: 4, maxHeight: 240, overflowY: 'auto' }}>
        {columns.map((c, i) => {
          const meta = fields.find((f) => f.name === c.field);
          return (
            <div key={c.field}
                 style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px',
                          borderBottom: i < columns.length - 1 ? '1px solid var(--color-bg-secondary)' : 'none' }}>
              <input type="checkbox" checked={c.visible !== false}
                     onChange={(e) => {
                       const next = [...columns];
                       next[i] = { ...c, visible: e.target.checked };
                       onChange(next);
                     }} />
              <span style={{ flex: 1, fontSize: 13 }}>
                {meta?.label ?? c.label ?? c.field}
                <span style={{ color: 'var(--color-text-muted)', fontSize: 11, marginLeft: 6 }}>({c.field})</span>
              </span>
              <input type="number" value={c.width ?? 160}
                     onChange={(e) => {
                       const next = [...columns];
                       next[i] = { ...c, width: Number(e.target.value) || 160 };
                       onChange(next);
                     }}
                     style={{ width: 60, padding: 2, fontSize: 12 }}
                     min={60} max={600} title="列宽(px)" />
              <span style={{ fontSize: 11, color: 'var(--color-text-disabled)' }}>px</span>
              <button type="button"
                      onClick={() => { if (i === 0) return; const next = [...columns]; [next[i - 1], next[i]] = [next[i], next[i - 1]]; onChange(next); }}
                      disabled={i === 0}
                      style={{ padding: '2px 6px', fontSize: 11, cursor: i === 0 ? 'not-allowed' : 'pointer', opacity: i === 0 ? 0.4 : 1 }}>↑</button>
              <button type="button"
                      onClick={() => { if (i === columns.length - 1) return; const next = [...columns]; [next[i], next[i + 1]] = [next[i + 1], next[i]]; onChange(next); }}
                      disabled={i === columns.length - 1}
                      style={{ padding: '2px 6px', fontSize: 11, cursor: i === columns.length - 1 ? 'not-allowed' : 'pointer', opacity: i === columns.length - 1 ? 0.4 : 1 }}>↓</button>
            </div>
          );
        })}
      </div>
      <div style={{ fontSize: 11, color: 'var(--color-text-disabled)', marginTop: 4 }}>
        取消勾选可隐藏列;上下箭头调整列顺序
      </div>
    </div>
  );
}

/* === 详情视图字段顺序编辑器 === */
function DetailFieldEditor({
  fields, selected, onChange,
}: {
  fields: FieldDef[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  return (
    <div style={{ marginBottom: 12 }}>
      <label style={{ display: 'block', fontWeight: 500, marginBottom: 6 }}>
        显示字段 ({selected.length}/{fields.length})
      </label>
      <div style={{ border: '1px solid var(--color-border-light)', borderRadius: 4, padding: 4, maxHeight: 240, overflowY: 'auto' }}>
        {fields.map((f) => {
          const idx = selected.indexOf(f.name);
          const checked = idx >= 0;
          return (
            <div key={f.name}
                 style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px',
                          borderBottom: '1px solid var(--color-bg-secondary)' }}>
              <input type="checkbox" checked={checked}
                     onChange={(e) => {
                       if (e.target.checked) onChange([...selected, f.name]);
                       else onChange(selected.filter((x) => x !== f.name));
                     }} />
              <span style={{ flex: 1, fontSize: 13 }}>
                {f.label ?? f.name}
                <span style={{ color: 'var(--color-text-muted)', fontSize: 11, marginLeft: 6 }}>({f.name})</span>
              </span>
              {checked && (
                <>
                  <button type="button"
                          onClick={() => { if (idx === 0) return; const next = [...selected]; [next[idx - 1], next[idx]] = [next[idx], next[idx - 1]]; onChange(next); }}
                          disabled={idx === 0}
                          style={{ padding: '2px 6px', fontSize: 11, cursor: idx === 0 ? 'not-allowed' : 'pointer', opacity: idx === 0 ? 0.4 : 1 }}>↑</button>
                  <button type="button"
                          onClick={() => { if (idx === selected.length - 1) return; const next = [...selected]; [next[idx], next[idx + 1]] = [next[idx + 1], next[idx]]; onChange(next); }}
                          disabled={idx === selected.length - 1}
                          style={{ padding: '2px 6px', fontSize: 11, cursor: idx === selected.length - 1 ? 'not-allowed' : 'pointer', opacity: idx === selected.length - 1 ? 0.4 : 1 }}>↓</button>
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
