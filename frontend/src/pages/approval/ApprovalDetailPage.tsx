import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import apiClient from '@/api/client';

interface TrailStep {
  taskId: string;
  nodeId?: string;
  assignee?: string;
  status: string;
  comment?: string;
  createdAt?: string;
  finishedAt?: string;
}

interface Detail {
  id: string;
  templateName?: string;
  status: string;
  submittedAt: string;
  finishedAt?: string;
  formData?: Record<string, unknown>;
  currentTaskId?: string;
  trail: TrailStep[];
  cc: string[];
}

/**
 * 审批详情（E2）：时间线 + 审批操作。
 *
 * <p>审批动作复用既有工作流任务端点（同一引擎、同一审计），
 * 不在审批模块里造第二套"通过/驳回"逻辑。
 */
export function ApprovalDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => {
    if (!id) return;
    apiClient
      .get<Detail>(`/approvals/${id}`)
      .then(r => setDetail(r))
      .catch(e => setError(e?.response?.data?.message ?? e?.message ?? '加载失败'));
  };

  useEffect(load, [id]);

  const act = async (action: 'approve' | 'reject') => {
    const taskId = detail?.currentTaskId;
    if (!taskId) {
      setError('当前没有待处理任务');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await apiClient.post(`/workflows/tasks/${taskId}/${action}`, { comment });
      setComment('');
      load();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    if (!id) return;
    if (!window.confirm('确认撤回该审批？')) return;
    setBusy(true);
    try {
      await apiClient.post(`/approvals/${id}/cancel`);
      navigate('/approvals');
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.message ?? '撤回失败');
    } finally {
      setBusy(false);
    }
  };

  if (!detail) {
    return <div style={{ padding: 20 }}>{error ?? '加载中…'}</div>;
  }

  return (
    <div style={{ padding: 20, maxWidth: 760 }} className="approval-print">
      {/* D8：打印样式 —— 隐藏导航/操作区，只留单据内容 */}
      <style>{`
        @media print {
          nav, aside, .no-print { display: none !important; }
          .approval-print { max-width: none !important; padding: 0 !important; }
          .approval-print section { break-inside: avoid; }
        }
      `}</style>
      <div className="no-print" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
        <button onClick={() => navigate(-1)}>← 返回</button>
        <button onClick={() => window.print()}>打印</button>
      </div>
      <h2 style={{ fontSize: 18 }}>{detail.templateName ?? '审批单'}</h2>
      <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 16 }}>
        状态：{detail.status} · 提交于 {new Date(detail.submittedAt).toLocaleString()}
        {detail.finishedAt && ` · 完成于 ${new Date(detail.finishedAt).toLocaleString()}`}
      </div>

      <section style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14, marginBottom: 16 }}>
        <h3 style={{ fontSize: 14, marginBottom: 8 }}>申请内容</h3>
        {Object.entries(detail.formData ?? {}).map(([k, v]) => (
          <div key={k} style={{ fontSize: 13, marginBottom: 4 }}>
            <span style={{ opacity: 0.6 }}>{k}：</span>
            {String(v ?? '')}
          </div>
        ))}
        {Object.keys(detail.formData ?? {}).length === 0 && <div style={{ opacity: 0.6 }}>（无表单内容）</div>}
      </section>

      <section style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14, marginBottom: 16 }}>
        <h3 style={{ fontSize: 14, marginBottom: 8 }}>审批记录</h3>
        {detail.trail.length === 0 && <div style={{ opacity: 0.6 }}>暂无记录</div>}
        {detail.trail.map(step => (
          <div key={step.taskId} style={{ display: 'flex', gap: 12, padding: '8px 0', borderBottom: '1px dashed var(--color-border-light)' }}>
            <div style={{ width: 100, fontSize: 12, opacity: 0.7 }}>{step.status}</div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13 }}>{step.nodeId ?? '审批节点'}</div>
              {step.comment && <div style={{ fontSize: 12, opacity: 0.7 }}>{step.comment}</div>}
              <div style={{ fontSize: 11, opacity: 0.5 }}>
                {step.finishedAt ? new Date(step.finishedAt).toLocaleString() : '待处理'}
              </div>
            </div>
          </div>
        ))}
      </section>

      {detail.status === 'PENDING' && detail.currentTaskId && (
        <section style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14 }}>
          <h3 style={{ fontSize: 14, marginBottom: 8 }}>审批操作</h3>
          <textarea
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="审批意见（可选）"
            rows={3}
            style={{ width: '100%', padding: '8px 10px', marginBottom: 10, borderRadius: 6, border: '1px solid var(--color-border-light)' }}
          />
          <div style={{ display: 'flex', gap: 8 }}>
            <button onClick={() => act('approve')} disabled={busy}>通过</button>
            <button onClick={() => act('reject')} disabled={busy} style={{ color: 'var(--color-error)' }}>驳回</button>
            <button onClick={cancel} disabled={busy}>撤回</button>
          </div>
          {error && <div style={{ color: 'var(--color-error)', marginTop: 8 }}>{error}</div>}
        </section>
      )}

      {detail.status === 'PENDING' && !detail.currentTaskId && (
        <section style={{ border: '1px solid var(--color-border-light)', borderRadius: 8, padding: 14 }}>
          <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 8 }}>
            当前没有指派给你的待办任务（等待其他审批人处理）。
          </div>
          <button onClick={cancel} disabled={busy}>撤回申请</button>
          {error && <div style={{ color: 'var(--color-error)', marginTop: 8 }}>{error}</div>}
        </section>
      )}
    </div>
  );
}
