/**
 * NocoBase CRDT 服务端合并服务
 * 
 * 服务端合并方案：
 * - 客户端发送 Yjs update（Base64）→ 服务端合并到文档状态 → 持久化到 PostgreSQL
 * - 新成员加入时从服务端获取完整文档状态，实现「服务端权威」
 * - 同时广播增量给其他客户端，保持实时同步
 * 
 * 数据库表结构：
 *   CREATE TABLE IF NOT EXISTS crdt_docs (
 *     doc_id TEXT PRIMARY KEY,
 *     state BYTEA NOT NULL,       -- Yjs 完整状态 (Y.encodeStateAsUpdate)
 *     version BIGINT DEFAULT 0,
 *     created_at TIMESTAMPTZ DEFAULT NOW(),
 *     updated_at TIMESTAMPTZ DEFAULT NOW()
 *   );
 */

import express from 'express';
import cors from 'cors';
import pg from 'pg';
import * as Y from 'yjs';

const { Pool } = pg;

const app = express();
const PORT = process.env.PORT || 3100;

// PostgreSQL 连接池
const pool = new Pool({
  host: process.env.PG_HOST || 'localhost',
  port: parseInt(process.env.PG_PORT || '5432', 10),
  database: process.env.PG_DB || 'nocobase',
  user: process.env.PG_USER || 'nocobase',
  password: process.env.PG_PASSWORD || 'dev_password',
  max: 20,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

// 中间件
app.use(express.json({ limit: '10mb' }));
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if (req.method === 'OPTIONS') return res.sendStatus(200);
  next();
});

// ==================== 工具函数 ====================

/** Base64 → Uint8Array */
function base64ToUint8Array(base64) {
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

/** Uint8Array → Base64 */
function uint8ArrayToBase64(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

// ==================== 服务端合并核心 ====================

/** 内存中的文档缓存（加速读取） */
const docCache = new Map(); // docId → { doc: Y.Doc, version: number }

/**
 * 获取文档（优先内存缓存，否则从数据库加载）
 */
async function getDocument(docId) {
  // 检查内存缓存
  if (docCache.has(docId)) {
    return docCache.get(docId);
  }

  // 从数据库加载
  const { rows } = await pool.query(
    'SELECT state, version FROM crdt_docs WHERE doc_id = $1',
    [docId]
  );

  if (rows.length > 0) {
    const doc = new Y.Doc();
    const state = rows[0].state;
    if (state && state.length > 0) {
      Y.applyUpdate(doc, new Uint8Array(state));
    }
    const cache = { doc, version: rows[0].version || 0 };
    docCache.set(docId, cache);
    console.log(`[CRDT] 从数据库加载文档 ${docId}，版本 ${rows[0].version}`);
    return cache;
  }

  // 创建新文档
  const doc = new Y.Doc();
  const cache = { doc, version: 0 };
  docCache.set(docId, cache);
  console.log(`[CRDT] 创建新文档 ${docId}`);
  return cache;
}

/**
 * 应用增量并持久化
 * 返回新的完整状态（Base64）供广播给其他客户端
 */
/**
 * 应用增量并持久化文档状态。
 * @param {string} docId
 * @param {string} updateBase64
 * @param {string} userId - 当前操作者 UUID（可为空字符串表示匿名）
 * @param {string} tenantId - 租户标识（可为空字符串）
 */
async function applyUpdate(docId, updateBase64, userId = '', tenantId = '') {
  if (!updateBase64) {
    throw new Error('Missing update');
  }

  const { doc, version } = await getDocument(docId);

  // 解码增量
  const updateBytes = base64ToUint8Array(updateBase64);

  // 记录更新前的版本号
  const beforeCount = doc.getUpdateCount();

  // 服务端合并：应用增量到文档状态
  Y.applyUpdate(doc, updateBytes);

  const afterCount = doc.getUpdateCount();
  if (afterCount === beforeCount) {
    console.warn(`[CRDT] 文档 ${docId} 版本未变（可能是重复更新）`);
  }

  // 新版本号
  const newVersion = version + 1;

  // 持久化到 PostgreSQL
  const stateBytes = Y.encodeStateAsUpdate(doc);
  await pool.query(`
    INSERT INTO crdt_docs (doc_id, state, version, updated_at)
    VALUES ($1, $2, $3, NOW())
    ON CONFLICT (doc_id)
    DO UPDATE SET
      state = EXCLUDED.state,
      version = EXCLUDED.version,
      updated_at = NOW()
  `, [docId, Buffer.from(stateBytes), newVersion]);

  // PHASE127: 同步写入版本快照（冲突检测与回滚的依据）
  try {
    await pool.query(`
      INSERT INTO crdt_doc_versions (doc_id, version, state, user_id, tenant_id)
      VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''))
      ON CONFLICT (doc_id, version) DO NOTHING
    `, [docId, newVersion, Buffer.from(stateBytes), userId, tenantId]);
  } catch (snapErr) {
    console.warn(`[CRDT] 版本快照写入失败（不阻断主流程）: ${snapErr.message}`);
  }

  // 更新内存缓存
  docCache.set(docId, { doc, version: newVersion });

  console.log(`[CRDT] 文档 ${docId} 更新到版本 ${newVersion}（内存 ${doc.getUpdateCount()} 次更新）`);

  return { docId, version: newVersion };
}

/**
 * 获取文档完整状态（Base64）
 */
async function getDocumentState(docId) {
  const { doc, version } = await getDocument(docId);
  const state = Y.encodeStateAsUpdate(doc);
  return { state: uint8ArrayToBase64(state), version };
}

// ==================== API 接口 ====================

/** 健康检查 */
app.get('/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', database: 'connected' });
  } catch (e) {
    res.status(503).json({ status: 'error', database: 'disconnected', error: e.message });
  }
});

/**
 * 获取文档状态
 * GET /docs/:docId/state
 * 返回: { state: base64, version: number }
 */
app.get('/docs/:docId/state', async (req, res) => {
  try {
    const { docId } = req.params;
    const { state, version } = await getDocumentState(docId);
    res.json({ state, version });
  } catch (e) {
    console.error('[CRDT] 获取状态失败:', e);
    res.status(500).json({ error: e.message });
  }
});

/**
 * 应用增量（服务端合并）
 * POST /docs/:docId/update
 * Body: { update: base64 }
 * 返回: { docId, version, state: base64 }
 */
app.post('/docs/:docId/update', async (req, res) => {
  try {
    const { docId } = req.params;
    const { update, userId, tenantId } = req.body;

    if (!update) {
      return res.status(400).json({ error: 'Missing update' });
    }

    // 服务端合并：应用增量 → 持久化 → 返回新状态
    const { version } = await applyUpdate(docId, update, userId, tenantId);

    // 返回完整新状态（供广播给其他客户端）
    const { state } = await getDocumentState(docId);

    res.json({ docId, version, state });
  } catch (e) {
    console.error('[CRDT] 应用增量失败:', e);
    res.status(500).json({ error: e.message });
  }
});

/**
 * 批量应用增量（用于恢复/同步）
 * POST /docs/:docId/updates
 * Body: { updates: [base64, ...] }
 */
app.post('/docs/:docId/updates', async (req, res) => {
  try {
    const { docId } = req.params;
    const { updates } = req.body;

    if (!Array.isArray(updates)) {
      return res.status(400).json({ error: 'updates must be array' });
    }

    const { doc } = await getDocument(docId);
    let applied = 0;
    for (const updateBase64 of updates) {
      try {
        Y.applyUpdate(doc, base64ToUint8Array(updateBase64));
        applied++;
      } catch (e) {
        console.warn(`[CRDT] 应用增量失败:`, e.message);
      }
    }

    // 持久化
    const stateBytes = Y.encodeStateAsUpdate(doc);
    const { version } = await getDocument(docId);
    await pool.query(`
      INSERT INTO crdt_docs (doc_id, state, version, updated_at)
      VALUES ($1, $2, $3, NOW())
      ON CONFLICT (doc_id)
      DO UPDATE SET state = EXCLUDED.state, version = $3, updated_at = NOW()
    `, [docId, Buffer.from(stateBytes), version + 1]);

    res.json({ applied, docId });
  } catch (e) {
    console.error('[CRDT] 批量应用失败:', e);
    res.status(500).json({ error: e.message });
  }
});

/**
 * PHASE127: 查询文档版本历史与冲突候选
 * GET /docs/:docId/conflicts?since=<version>
 *
 * 返回:
 * {
 *   docId,
 *   currentVersion: number,
 *   since: number,
 *   conflicting: boolean,        // since 之后是否有他人改动
 *   versions: [{ version, createdAt, size }]
 * }
 *
 * <p>判定逻辑：客户端提交本地基线版本 since，若服务端 currentVersion > since，
 * 说明期间存在其他成员的提交，即构成潜在冲突，需前端拉取差异并人工/自动合并。
 */
app.get('/docs/:docId/conflicts', async (req, res) => {
  try {
    const { docId } = req.params;
    const sinceRaw = req.query.since;
    const since = sinceRaw === undefined ? 0 : Number.parseInt(String(sinceRaw), 10);

    if (!Number.isFinite(since) || since < 0) {
      return res.status(400).json({ error: 'Invalid since parameter' });
    }

    const cur = await pool.query(
      'SELECT version FROM crdt_docs WHERE doc_id = $1',
      [docId],
    );
    const currentVersion = cur.rowCount === 0 ? 0 : Number(cur.rows[0].version);

    const rows = await pool.query(
      `SELECT version, created_at, octet_length(state) AS size
         FROM crdt_doc_versions
        WHERE doc_id = $1 AND version > $2
        ORDER BY version ASC`,
      [docId, since],
    );

    res.json({
      docId,
      currentVersion,
      since,
      conflicting: currentVersion > since,
      versions: rows.rows.map((r) => ({
        version: Number(r.version),
        createdAt: r.created_at,
        size: Number(r.size),
      })),
    });
  } catch (e) {
    console.error('[CRDT] 查询冲突失败:', e);
    res.status(500).json({ error: e.message });
  }
});

/**
 * PHASE127: 回滚到指定版本（以历史快照覆盖当前状态，产生一个新版本）
 * POST /docs/:docId/rollback
 * Body: { version: number }
 * 返回: { docId, version: 新版本号, state: base64 }
 */
app.post('/docs/:docId/rollback', async (req, res) => {
  try {
    const { docId } = req.params;
    const { version: versionRaw, userId, tenantId } = req.body;
    const targetVersion = Number.parseInt(String(versionRaw), 10);

    if (!Number.isFinite(targetVersion) || targetVersion < 1) {
      return res.status(400).json({ error: 'Invalid version' });
    }

    const snap = await pool.query(
      'SELECT state FROM crdt_doc_versions WHERE doc_id = $1 AND version = $2',
      [docId, targetVersion],
    );
    if (snap.rowCount === 0) {
      return res.status(404).json({ error: 'Version not found' });
    }

    const cur = await pool.query(
      'SELECT version FROM crdt_docs WHERE doc_id = $1',
      [docId],
    );
    const newVersion = (cur.rowCount === 0 ? 0 : Number(cur.rows[0].version)) + 1;
    const restored = snap.rows[0].state;

    await pool.query(`
      INSERT INTO crdt_docs (doc_id, state, version, updated_at)
      VALUES ($1, $2, $3, NOW())
      ON CONFLICT (doc_id)
      DO UPDATE SET state = EXCLUDED.state, version = EXCLUDED.version, updated_at = NOW()
    `, [docId, restored, newVersion]);

    await pool.query(`
      INSERT INTO crdt_doc_versions (doc_id, version, state, user_id, tenant_id)
      VALUES ($1, $2, $3, NULLIF($4, ''), NULLIF($5, ''))
      ON CONFLICT (doc_id, version) DO NOTHING
    `, [docId, newVersion, restored, userId, tenantId]);

    res.json({
      docId,
      version: newVersion,
      rolledBackFrom: targetVersion,
      state: Buffer.from(restored).toString('base64'),
    });
  } catch (e) {
    console.error('[CRDT] 回滚失败:', e);
    res.status(500).json({ error: e.message });
  }
});

// ==================== 启动 ====================

async function init() {
  // 创建表（如不存在）
  await pool.query(`
    CREATE TABLE IF NOT EXISTS crdt_docs (
      doc_id TEXT PRIMARY KEY,
      state BYTEA NOT NULL,
      version BIGINT DEFAULT 0,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    )
  `);
  // PHASE127: 冲突版本快照表（用于冲突检测与回滚）
  await pool.query(`
    CREATE TABLE IF NOT EXISTS crdt_doc_versions (
      doc_id TEXT NOT NULL,
      version BIGINT NOT NULL,
      state BYTEA NOT NULL,
      user_id UUID,
      tenant_id TEXT,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      PRIMARY KEY (doc_id, version)
    )
  `);
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_crdt_doc_versions_lookup
    ON crdt_doc_versions(doc_id, version DESC)
  `);
  console.log('[CRDT] 数据库表初始化完成 (含 PHASE127 版本表)');

  app.listen(PORT, () => {
    console.log(`[CRDT] 服务端合并服务启动: http://localhost:${PORT}`);
  });
}

init().catch(console.error);

export default app;