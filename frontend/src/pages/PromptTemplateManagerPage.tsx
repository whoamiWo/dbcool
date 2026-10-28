import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import apiClient from '@/api/client';
import { ErrorState } from '@/components/ErrorState';
import { EmptyState } from '@/components/EmptyState';

interface PromptTemplate {
  id: string;
  name: string;
  description?: string;
  prompt_template: string;
  created_at: string;
  updated_at?: string;
}

export function PromptTemplateManagerPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formData, setFormData] = useState({ name: '', description: '', prompt_template: '' });
  const [fillModalOpen, setFillModalOpen] = useState(false);
  const [selectedTemplate, setSelectedTemplate] = useState<PromptTemplate | null>(null);
  const [fillValues, setFillValues] = useState<{ [key: string]: string }>({});
  const [filledResult, setFilledResult] = useState('');

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['prompt-templates'],
    queryFn: async () => {
      const r = await apiClient.get<{ code: number; data: PromptTemplate[] }>('/ai/prompt-templates');
      if (Array.isArray(r)) return r;
      return r.data ?? [];
    },
  });

  const createMutation = useMutation({
    mutationFn: (data: { name: string; description?: string; prompt_template: string }) =>
      apiClient.post<{ code: number }>(`/ai/prompt-templates`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['prompt-templates'] });
      setShowCreate(false);
      setFormData({ name: '', description: '', prompt_template: '' });
    },
    onError: (err: unknown) => {
      console.error('创建模板失败', err);
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: { name: string; description?: string; prompt_template: string } }) =>
      apiClient.put<{ code: number }>(`/ai/prompt-templates/${id}`, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['prompt-templates'] });
      setEditingId(null);
      setFormData({ name: '', description: '', prompt_template: '' });
    },
    onError: (err: unknown) => {
      console.error('更新模板失败', err);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) =>
      apiClient.delete<{ code: number }>(`/ai/prompt-templates/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['prompt-templates'] });
    },
    onError: (err: unknown) => {
      console.error('删除模板失败', err);
    },
  });

  const fillMutation = useMutation({
    mutationFn: ({ id, values }: { id: string; values: { [key: string]: string } }) =>
      apiClient.post<{ code: number; data: { filled_prompt: string } }>(
        `/ai/prompt-templates/${id}/fill`,
        { values }
      ),
    onSuccess: (res) => {
      const r = Array.isArray(res) ? res[0] : res;
      setFilledResult(r?.data?.filled_prompt || '');
    },
    onError: (err: unknown) => {
      console.error('填充失败', err);
    },
  });

  const extractVariables = (template: string): string[] => {
    const matches = template.match(/\{\{(.*?)\}\}/g) || [];
    return [...new Set(matches.map(m => m.slice(2, -2)))];
  };

  const openFillModal = (template: PromptTemplate) => {
    setSelectedTemplate(template);
    const vars = extractVariables(template.prompt_template);
    const initialValues: { [key: string]: string } = {};
    vars.forEach(v => { initialValues[v] = ''; });
    setFillValues(initialValues);
    setFilledResult('');
    setFillModalOpen(true);
  };

  const handleSave = () => {
    if (!formData.name || !formData.prompt_template) {
      return;
    }
    if (editingId) {
      updateMutation.mutate({ id: editingId, data: formData });
    } else {
      createMutation.mutate(formData);
    }
  };

  const handleFill = () => {
    if (!selectedTemplate) return;
    fillMutation.mutate({ id: selectedTemplate.id, values: fillValues });
  };

  if (isLoading) return <p>{t('common.loading')}</p>;

  if (error) {
    return (
      <div>
        <h1>{t('promptTemplates.pageTitle', 'AI 提示词模板管理')}</h1>
        <ErrorState
          message={t('promptTemplates.loadFailed', '加载模板失败')}
          onRetry={() => void refetch()}
        />
      </div>
    );
  }

  const templates: PromptTemplate[] = data ?? [];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1>{t('promptTemplates.pageTitle', 'AI 提示词模板管理')} ({templates.length})</h1>
        <button
          onClick={() => setShowCreate(true)}
          style={{
            padding: '8px 16px',
            background: 'var(--color-primary)',
            color: '#fff',
            border: 'none',
            borderRadius: 4,
            cursor: 'pointer',
          }}
        >
          {t('promptTemplates.newTemplate', '新建模板')}
        </button>
      </div>

      {/* Create/Edit Panel */}
      {(showCreate || editingId) && (
        <div style={{ padding: 16, background: 'var(--color-bg-secondary)', borderRadius: 8, marginBottom: 16 }}>
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
              {t('promptTemplates.name', '模板名称')}
            </label>
            <input
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              style={{
                padding: 8,
                width: '100%',
                borderRadius: 4,
                border: '1px solid var(--color-border)',
              }}
              placeholder={t('promptTemplates.namePlaceholder', '例如：Java REST API 生成器')}
            />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
              {t('promptTemplates.description', '描述')}
            </label>
            <textarea
              value={formData.description}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              rows={2}
              style={{
                padding: 8,
                width: '100%',
                borderRadius: 4,
                border: '1px solid var(--color-border)',
                resize: 'vertical',
              }}
              placeholder={t('promptTemplates.descriptionPlaceholder', '简短描述此模板的用途')}
            />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>
              {t('promptTemplates.template', '提示词模板')}
            </label>
            <textarea
              value={formData.prompt_template}
              onChange={(e) => setFormData({ ...formData, prompt_template: e.target.value })}
              rows={6}
              style={{
                padding: 8,
                width: '100%',
                borderRadius: 4,
                border: '1px solid var(--color-border)',
                fontFamily: 'monospace',
                resize: 'vertical',
              }}
              placeholder={t('promptTemplates.templatePlaceholder', '可使用 {{变量名}} 格式，例如：请生成一个 {{language}} 语言的 {{功能}} 功能')}
            />
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              onClick={handleSave}
              disabled={!formData.name || !formData.prompt_template || (editingId ? updateMutation.isPending : createMutation.isPending)}
              style={{
                padding: '8px 16px',
                background: 'var(--color-success)',
                color: '#fff',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                opacity: (!formData.name || !formData.prompt_template) ? 0.6 : 1,
              }}
            >
              {(editingId ? updateMutation.isPending : createMutation.isPending)
                ? t('common.saving')
                : t('promptTemplates.save', '保存')}
            </button>
            <button
              onClick={() => { setShowCreate(false); setEditingId(null); }}
              style={{
                padding: '8px 16px',
                background: 'var(--color-bg-tertiary)',
                color: 'var(--color-text-primary)',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
              }}
            >
              {t('common.cancel')}
            </button>
          </div>
        </div>
      )}

      {/* Fill Template Modal */}
      {fillModalOpen && selectedTemplate && (
        <div style={{
          position: 'fixed',
          top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.5)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 1000,
        }}>
          <div style={{
            background: 'var(--color-bg-paper)',
            borderRadius: 8,
            padding: 24,
            maxWidth: 700,
            width: '90%',
            maxHeight: '80vh',
            overflow: 'auto',
          }}>
            <h3 style={{ marginBottom: 16 }}>{t('promptTemplates.fillVariables', '填充变量')}：{selectedTemplate.name}</h3>
            
            {Object.keys(fillValues).length === 0 ? (
              <p style={{ color: 'var(--color-text-disabled)' }}>{t('promptTemplates.noVariables', '该模板没有变量占位符')}</p>
            ) : (
              <>
                <p style={{ marginBottom: 12 }}>{t('promptTemplates.fillInstructions', '请填写以下变量的值：')}</p>
                {Object.keys(fillValues).map(varName => (
                  <div key={varName} style={{ marginBottom: 12 }}>
                    <label style={{ display: 'block', marginBottom: 4, fontWeight: 500 }}>{varName}</label>
                    <input
                      value={fillValues[varName]}
                      onChange={(e) => setFillValues({ ...fillValues, [varName]: e.target.value })}
                      style={{
                        padding: 8,
                        width: '100%',
                        borderRadius: 4,
                        border: '1px solid var(--color-border)',
                      }}
                      placeholder={`请输入 ${varName}`}
                    />
                  </div>
                ))}
                
                <div style={{ marginTop: 16 }}>
                  <button
                    onClick={handleFill}
                    disabled={fillMutation.isPending}
                    style={{
                      padding: '8px 16px',
                      background: 'var(--color-primary)',
                      color: '#fff',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                      marginRight: 8,
                    }}
                  >
                    {fillMutation.isPending ? t('common.loading') : t('promptTemplates.preview', '预览')}
                  </button>
                  <button
                    onClick={() => setFillModalOpen(false)}
                    style={{
                      padding: '8px 16px',
                      background: 'var(--color-bg-tertiary)',
                      color: 'var(--color-text-primary)',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                    }}
                  >
                    {t('common.close')}
                  </button>
                </div>
              </>
            )}

            {filledResult && (
              <div style={{ marginTop: 16 }}>
                <h4>{t('promptTemplates.result', '预览结果')}</h4>
                <textarea
                  value={filledResult}
                  readOnly
                  rows={6}
                  style={{
                    width: '100%',
                    padding: 8,
                    borderRadius: 4,
                    border: '1px solid var(--color-border)',
                    fontFamily: 'monospace',
                  }}
                />
              </div>
            )}
          </div>
        </div>
      )}

      {/* Templates Table */}
      {templates.length === 0 ? (
        <EmptyState
          title={t('promptTemplates.emptyTitle', '暂无模板')}
          description={t('promptTemplates.emptyDesc', '点击"新建模板"创建第一个提示词模板')}
        />
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 16 }}>
          <thead>
            <tr style={{ background: 'var(--color-bg-secondary)' }}>
              <th style={{ padding: 12, textAlign: 'left' }}>{t('promptTemplates.nameCol', '名称')}</th>
              <th style={{ padding: 12, textAlign: 'left' }}>{t('promptTemplates.descriptionCol', '描述')}</th>
              <th style={{ padding: 12, textAlign: 'left' }}>{t('promptTemplates.variablesCol', '变量')}</th>
              <th style={{ padding: 12, textAlign: 'left' }}>{t('promptTemplates.updatedAtCol', '更新时间')}</th>
              <th style={{ padding: 12, textAlign: 'right' }}>{t('promptTemplates.actionsCol', '操作')}</th>
            </tr>
          </thead>
          <tbody>
            {templates.map((tpl) => {
              const variables = extractVariables(tpl.prompt_template);
              return (
                <tr key={tpl.id} style={{ borderTop: '1px solid var(--color-border-light)' }}>
                  <td style={{ padding: 12, fontWeight: 500 }}>{tpl.name}</td>
                  <td style={{ padding: 12, color: 'var(--color-text-secondary)' }}>{tpl.description || '-'}</td>
                  <td style={{ padding: 12 }}>
                    {variables.length === 0 ? (
                      <span style={{ color: 'var(--color-text-disabled)' }}>-</span>
                    ) : (
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                        {variables.slice(0, 5).map(v => (
                          <span
                            key={v}
                            style={{
                              padding: '2px 8px',
                              background: 'var(--color-info)',
                              color: '#fff',
                              borderRadius: 12,
                              fontSize: 12,
                            }}
                          >
                            {v}
                          </span>
                        ))}
                        {variables.length > 5 && (
                          <span style={{ padding: '2px 8px', background: 'var(--color-bg-tertiary)', borderRadius: 12, fontSize: 12 }}>
                            +{variables.length - 5}
                          </span>
                        )}
                      </div>
                    )}
                  </td>
                  <td style={{ padding: 12, color: 'var(--color-text-disabled)' }}>
                    {tpl.updated_at ? new Date(tpl.updated_at).toLocaleString('zh-CN') : '-'}
                  </td>
                  <td style={{ padding: 12, textAlign: 'right' }}>
                    <button
                      onClick={() => openFillModal(tpl)}
                      style={{
                        padding: '4px 8px',
                        marginRight: 8,
                        background: 'var(--color-bg-secondary)',
                        color: 'var(--color-text-primary)',
                        border: 'none',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}
                    >
                      {t('promptTemplates.fill', '填充')}
                    </button>
                    <button
                      onClick={() => {
                        setEditingId(tpl.id);
                        setFormData({
                          name: tpl.name,
                          description: tpl.description || '',
                          prompt_template: tpl.prompt_template,
                        });
                        setShowCreate(false);
                      }}
                      style={{
                        padding: '4px 8px',
                        marginRight: 8,
                        background: 'var(--color-bg-secondary)',
                        color: 'var(--color-text-primary)',
                        border: 'none',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}
                    >
                      {t('promptTemplates.edit', '编辑')}
                    </button>
                    <button
                      onClick={() => deleteMutation.mutate(tpl.id)}
                      disabled={deleteMutation.isPending && deleteMutation.variables === tpl.id}
                      style={{
                        padding: '4px 8px',
                        background: 'var(--color-error)',
                        color: '#fff',
                        border: 'none',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}
                    >
                      {deleteMutation.isPending && deleteMutation.variables === tpl.id
                        ? t('common.deleting')
                        : t('promptTemplates.delete', '删除')}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default PromptTemplateManagerPage;
