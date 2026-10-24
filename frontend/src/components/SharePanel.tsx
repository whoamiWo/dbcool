import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';

/**
 * 视图分享面板 — US-207 / US-306
 * 支持添加用户或角色共享，并保存 shared_with_json
 */
export interface ShareEntry {
  type: 'user' | 'role';
  name: string;
}

interface SharePanelProps {
  /** 已存在的视图 ID；为空表示尚未保存(仅本地编辑)。 */
  viewId?: string;
  initialShares?: ShareEntry[];
  /** 分享项变更时同步给父组件,便于主保存一并落库。 */
  onChange?: (shares: ShareEntry[]) => void;
  /** 面板内直接落库(已存在视图时可用)。 */
  onSave?: (shares: ShareEntry[]) => Promise<void>;
}

export function SharePanel({ viewId, initialShares = [], onChange, onSave }: SharePanelProps) {
  const { t } = useTranslation('sharePanel');
  const queryClient = useQueryClient();
  const [entries, setEntries] = useState<ShareEntry[]>(initialShares);
  const [newType, setNewType] = useState<'user' | 'role'>('user');
  const [newName, setNewName] = useState('');

  // 外部数据回填(编辑已有视图时)
   
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 回填初始 shares，guarded 比较避免无效渲染
    setEntries((prev) =>
      prev.length === initialShares.length &&
        prev.every((p, i) => p.id === initialShares[i]?.id)
        ? prev
        : initialShares
    );
  }, [initialShares]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!onSave) return;
      await onSave(entries);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['views'] });
      if (viewId) queryClient.invalidateQueries({ queryKey: ['view', viewId] });
    },
  });

  const update = (next: ShareEntry[]) => {
    setEntries(next);
    onChange?.(next);
  };

  const addEntry = () => {
    const name = newName.trim();
    if (!name) return;
    update([...entries, { type: newType, name }]);
    setNewName('');
  };

  const removeEntry = (index: number) => {
    update(entries.filter((_, i) => i !== index));
  };

  return (
    <div style={{ padding: 12, background: 'var(--color-bg-secondary)', borderRadius: 8, marginTop: 16 }}>
      <h3 style={{ margin: '0 0 12px', fontSize: 14 }}>{t('title')}</h3>

      {entries.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          {entries.map((entry, i) => (
            <div
              key={`${entry.type}:${entry.name}:${i}`}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 8px',
                background: 'var(--color-text-primary)',
                borderRadius: 4,
                marginBottom: 6,
              }}
            >
              <span
                style={{
                  padding: '2px 6px',
                  background: entry.type === 'user' ? 'var(--color-info-100)' : 'var(--color-success-100)',
                  borderRadius: 4,
                  fontSize: 11,
                  minWidth: 40,
                  textAlign: 'center',
                }}
              >
                {t(entry.type)}
              </span>
              <span style={{ flex: 1, fontSize: 13 }}>{entry.name}</span>
              <button
                type="button"
                aria-label={t('deleteConfirm')}
                onClick={() => removeEntry(i)}
                style={{
                  padding: '2px 6px',
                  fontSize: 11,
                  background: 'var(--color-error)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: 4,
                  cursor: 'pointer',
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <select
          aria-label={t('typeLabel')}
          value={newType}
          onChange={(e) => setNewType(e.target.value as 'user' | 'role')}
          style={{ padding: 6, borderRadius: 4, border: '1px solid var(--color-border-light)' }}
        >
          <option value="user">{t('user')}</option>
          <option value="role">{t('role')}</option>
        </select>

        <input
          aria-label={t('nameLabel')}
          placeholder={newType === 'user' ? t('userPlaceholder') : t('rolePlaceholder')}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              addEntry();
            }
          }}
          style={{
            flex: 1,
            minWidth: 120,
            padding: 6,
            borderRadius: 4,
            border: '1px solid var(--color-border-light)',
          }}
        />

        <button
          type="button"
          onClick={addEntry}
          disabled={!newName.trim()}
          style={{
            padding: '6px 12px',
            background: !newName.trim() ? 'var(--color-text-disabled)' : 'var(--color-bg-secondary)',
            color: 'var(--color-text-primary)',
            border: 'none',
            borderRadius: 4,
            cursor: !newName.trim() ? 'not-allowed' : 'pointer',
            fontSize: 12,
          }}
        >
          {newType === 'user' ? t('addUser') : t('addRole')}
        </button>
      </div>

      {onSave && (
        <div style={{ marginTop: 12, textAlign: 'right' }}>
          <button
            type="button"
            onClick={() => saveMutation.mutate()}
            disabled={saveMutation.isPending}
            style={{
              padding: '8px 16px',
              background: saveMutation.isPending ? 'var(--color-text-disabled)' : 'var(--color-bg-secondary)',
              color: 'var(--color-text-primary)',
              border: 'none',
              borderRadius: 4,
              cursor: saveMutation.isPending ? 'not-allowed' : 'pointer',
            }}
          >
            {saveMutation.isPending ? t('savingShare') : t('saveShare')}
          </button>
        </div>
      )}
    </div>
  );
}

/** 把 ShareEntry[] 序列化为后端 shared_with JSON 字符串。 */
export function serializeShares(shares: ShareEntry[]): string {
  return JSON.stringify(shares.map((e) => `${e.type}:${e.name}`));
}

/** 解析后端 shared_with_json,失败返回空数组。 */
export function parseShares(json: string | undefined | null): ShareEntry[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json) as unknown;
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((x): x is string => typeof x === 'string' && x.includes(':'))
      .map((s) => {
        const idx = s.indexOf(':');
        const type = s.slice(0, idx);
        return {
          type: type === 'role' ? 'role' : 'user',
          name: s.slice(idx + 1),
        };
      });
  } catch {
    return [];
  }
}
