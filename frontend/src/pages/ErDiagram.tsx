import { useEffect, useState, useRef } from 'react';
import apiClient from '@/api/client';

/**
 * 数据模型 ER 图 (Week 14.5 P3-5).
 *
 * - 节点 = collection(矩形 + 字段数)
 * - 边 = belongsTo(实线) / hasMany(虚线) 关系
 * - 自带简易 force-directed 布局(无依赖)
 * - 可拖动,hover 关系高亮
 */

interface ErNode {
  name: string;
  title: string;
  field_count: number;
  fields_preview: string[];
  x: number;
  y: number;
  vx: number;
  vy: number;
}

interface ErEdge {
  source: string;
  target: string;
  field: string;
  type: 'belongsTo' | 'hasMany';
}

interface ErPayload {
  nodes: Array<{
    name: string;
    title: string;
    field_count: number;
    fields_preview: string[];
    x: number;
    y: number;
  }>;
  edges: ErEdge[];
  stats: { collections: number; relationships: number };
}

const NODE_W = 200;
const NODE_H = 80;
const VIEW_W = 1600;
const VIEW_H = 1000;

export function ErDiagramPage() {
  const [data, setData] = useState<ErPayload | null>(null);
  const [error, setError] = useState<string>('');
  const [hoverEdge, setHoverEdge] = useState<number | null>(null);
  const [hoverNode, setHoverNode] = useState<string | null>(null);
  const [, force] = useState(0);
  const dragRef = useRef<{ i: number; dx: number; dy: number } | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);

  // 加载 + 布局
  useEffect(() => {
    apiClient.get<ErPayload>('/admin/er-diagram')
      .then((j) => {
        // 初始:圆形摆放
        const N = j.nodes.length || 1;
        const r = Math.min(VIEW_W, VIEW_H) * 0.35;
        const placed: ErNode[] = j.nodes.map((n, i) => {
          const ang = (i / N) * Math.PI * 2;
          return {
            ...n,
            x: VIEW_W / 2 + Math.cos(ang) * r,
            y: VIEW_H / 2 + Math.sin(ang) * r,
            vx: 0,
            vy: 0,
          };
        });
        // 简易 force-directed:60 tick
        const idx = new Map(placed.map((n, i) => [n.name, i] as const));
        for (let tick = 0; tick < 60; tick++) {
          for (let i = 0; i < placed.length; i++) {
            for (let k = i + 1; k < placed.length; k++) {
              const dx = placed[i].x - placed[k].x;
              const dy = placed[i].y - placed[k].y;
              const d2 = dx * dx + dy * dy + 1;
              const f = 8000 / d2;
              const d = Math.sqrt(d2);
              placed[i].vx += (dx / d) * f;
              placed[k].vx -= (dx / d) * f;
              placed[i].vy += (dy / d) * f;
              placed[k].vy -= (dy / d) * f;
            }
          }
          for (const e of j.edges) {
            const i = idx.get(e.source);
            const k = idx.get(e.target);
            if (i === undefined || k === undefined) continue;
            const dx = placed[k].x - placed[i].x;
            const dy = placed[k].y - placed[i].y;
            const d = Math.sqrt(dx * dx + dy * dy) + 0.1;
            const target = 220;
            const f = (d - target) * 0.02;
            placed[i].vx += (dx / d) * f;
            placed[k].vx -= (dx / d) * f;
            placed[i].vy += (dy / d) * f;
            placed[k].vy -= (dy / d) * f;
          }
          for (const n of placed) {
            n.vx *= 0.6; n.vy *= 0.6;
            n.x += n.vx; n.y += n.vy;
            n.x = Math.max(NODE_W, Math.min(VIEW_W - NODE_W, n.x));
            n.y = Math.max(NODE_H, Math.min(VIEW_H - NODE_H, n.y));
          }
        }
        setData({ ...j, nodes: j.nodes.map((n) => {
          const p = placed.find((x) => x.name === n.name)!;
          return { ...n, x: p.x, y: p.y };
        })});
      })
      .catch((e) => setError(String(e?.message ?? e)));
  }, []);

  const onMouseDown = (i: number, e: React.MouseEvent) => {
    if (!data) return;
    const rect = svgRef.current!.getBoundingClientRect();
    const sx = (e.clientX - rect.left) * (VIEW_W / rect.width);
    const sy = (e.clientY - rect.top) * (VIEW_H / rect.height);
    dragRef.current = { i, dx: data.nodes[i].x - sx, dy: data.nodes[i].y - sy };
  };
  const onMouseMove = (e: React.MouseEvent) => {
    if (!dragRef.current || !data) return;
    const rect = svgRef.current!.getBoundingClientRect();
    const sx = (e.clientX - rect.left) * (VIEW_W / rect.width);
    const sy = (e.clientY - rect.top) * (VIEW_H / rect.height);
    data.nodes[dragRef.current.i].x = sx + dragRef.current.dx;
    data.nodes[dragRef.current.i].y = sy + dragRef.current.dy;
    force((x) => x + 1);
  };
  const onMouseUp = () => { dragRef.current = null; };

  if (error) return <div style={{ padding: 24, color: 'crimson' }}>{error}</div>;
  if (!data) return <div style={{ padding: 24 }}>加载中…</div>;

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>📊 数据模型 ER 图</h2>
        <div style={{ display: 'flex', gap: 16, color: 'var(--color-bg-elevated)', fontSize: 14 }}>
          <span><b>{data.stats.collections}</b> 张表</span>
          <span><b>{data.stats.relationships}</b> 条关系</span>
          <span style={{ color: 'var(--color-text-muted)' }}>可拖动节点</span>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 16, marginBottom: 8, fontSize: 13, color: 'var(--color-bg-elevated)' }}>
        <span>─── belongsTo 属于</span>
        <span>┄┄┄ hasMany 拥有多个</span>
        <span style={{ color: 'var(--color-text-muted)' }}>🟢 系统表 · 🔵 业务表 · 🟠 ER demo</span>
      </div>

      <div
        style={{
          border: '1px solid var(--color-border-light)',
          borderRadius: 8,
          background: 'var(--color-bg-tertiary)',
          overflow: 'hidden',
        }}
        onMouseMove={onMouseMove}
        onMouseUp={onMouseUp}
        onMouseLeave={onMouseUp}
      >
        <svg
          ref={svgRef}
          viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
          style={{ width: '100%', height: '70vh', display: 'block', cursor: 'default' }}
        >
          <defs>
            <marker id="arrowBelongsTo" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L0,6 L9,3 z" fill="var(--color-info)" />
            </marker>
            <marker id="arrowHasMany" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L0,6 L9,3 z" fill="var(--color-secondary-500)" />
            </marker>
          </defs>

          {data.edges.map((e, i) => {
            const s = data.nodes.find((n) => n.name === e.source);
            const t = data.nodes.find((n) => n.name === e.target);
            if (!s || !t) return null;
            const isActive = hoverEdge === i || hoverNode === e.source || hoverNode === e.target;
            const stroke = e.type === 'belongsTo' ? 'var(--color-info)' : 'var(--color-secondary-500)';
            return (
              <g key={i}>
                <line
                  x1={s.x} y1={s.y} x2={t.x} y2={t.y}
                  stroke={stroke}
                  strokeWidth={isActive ? 3 : 1.5}
                  strokeDasharray={e.type === 'hasMany' ? '6,4' : ''}
                  markerEnd={e.type === 'belongsTo' ? 'url(#arrowBelongsTo)' : 'url(#arrowHasMany)'}
                  opacity={isActive ? 1 : 0.55}
                  onMouseEnter={() => setHoverEdge(i)}
                  onMouseLeave={() => setHoverEdge(null)}
                  style={{ cursor: 'pointer' }}
                />
                {isActive && (
                  <text
                    x={(s.x + t.x) / 2}
                    y={(s.y + t.y) / 2 - 6}
                    fill={stroke}
                    fontSize="13"
                    fontWeight="600"
                    textAnchor="middle"
                    style={{ pointerEvents: 'none' }}
                  >
                    {e.field} ({e.type})
                  </text>
                )}
              </g>
            );
          })}

          {data.nodes.map((n, i) => {
            const isEr = n.name.startsWith('er_');
            const isSystem = ['user', 'role', 'user_role', 'collection_meta', 'audit_log', 'form', 'view', 'workflow'].some((s) => n.name === s);
            const fill = isEr ? 'rgba(255,158,11,0.1)' : isSystem ? 'rgba(16,185,129,0.1)' : 'rgba(59,130,246,0.1)';
            const stroke = isEr ? 'var(--color-warning)' : isSystem ? 'var(--color-success)' : 'var(--color-info)';
            return (
              <g
                key={n.name}
                transform={`translate(${n.x - NODE_W / 2}, ${n.y - NODE_H / 2})`}
                onMouseDown={(e) => onMouseDown(i, e)}
                onMouseEnter={() => setHoverNode(n.name)}
                onMouseLeave={() => setHoverNode(null)}
                style={{ cursor: 'grab' }}
              >
                <rect
                  width={NODE_W}
                  height={NODE_H}
                  rx={8}
                  fill={fill}
                  stroke={stroke}
                  strokeWidth={hoverNode === n.name ? 3 : 1.5}
                />
                <text x={12} y={22} fontSize="14" fontWeight="700" fill="var(--color-bg-primary)">
                  {n.title || n.name}
                </text>
                <text x={12} y={40} fontSize="11" fill="var(--color-text-disabled)">
                  {n.name} · {n.field_count} 字段
                </text>
                <text x={12} y={60} fontSize="11" fill="var(--color-bg-elevated)">
                  {n.fields_preview.slice(0, 3).join(', ')}{n.fields_preview.length > 3 ? '…' : ''}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}
