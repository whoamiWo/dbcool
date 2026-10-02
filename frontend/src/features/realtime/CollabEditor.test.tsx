import { describe, expect, it } from 'vitest';
import * as Y from 'yjs';
import { applyDiffToText, computeDiff } from './CollabEditor';

/**
 * PHASE70 T1 真实单测。
 *
 * 重要：本文件 **import 组件里真实的 computeDiff / applyDiffToText**，
 * 而不是把实现复制进来 —— 此前 frontend/e2e/collab-crdt.spec.ts 采用复制实现的
 * 写法，与组件行为完全隔离（即便组件改回整篇替换也全绿），零约束力。
 *
 * 覆盖两类：
 *  1. diff 本身是字符级增量
 *  2. **双端并发编辑**在真实 Yjs 文档上合并后双方修改都保留（核心验收）
 *  3. 反向验证：若退回整篇替换，并发会产生重复内容 —— 证明第 2 条确有约束力
 */

function createDoc(initial: string) {
  const doc = new Y.Doc();
  const text = doc.getText('content');
  if (initial) text.insert(0, initial);
  return { doc, text };
}

/** 模拟一次本地编辑：old → new 经字符级 diff 应用到 Y.Text。 */
function editByDiff(text: Y.Text, oldText: string, newText: string) {
  applyDiffToText(text, oldText, newText);
}

/** 模拟整篇替换（PHASE70 之前的错误实现），仅用于反向验证。 */
function editByReplaceAll(text: Y.Text, newText: string) {
  text.delete(0, text.length);
  text.insert(0, newText);
}

/** 双向交换 update，模拟 A、B 收到对方增量。 */
function sync(a: Y.Doc, b: Y.Doc) {
  Y.applyUpdate(a, Y.encodeStateAsUpdate(b));
  Y.applyUpdate(b, Y.encodeStateAsUpdate(a));
}

/**
 * 建立"同一份初始内容"的双端。
 *
 * 注意：初始内容**只能由一端插入**，另一端通过 sync 获取 —— 若两端各自
 * insert 同一段文本，Yjs 会视为两组不同字符而保留两份（那是我们构造上的错误，
 * 不是协同实现的缺陷）。
 */
function createPair(base: string) {
  const a = createDoc(base);
  const b = createDoc('');
  sync(a.doc, b.doc);
  return { a, b };
}

describe('computeDiff（字符级增量）', () => {
  it('尾部追加：只产生 insert，不重建全文', () => {
    const diffs = computeDiff('abc', 'abcde');
    expect(diffs.filter((d) => d.op === 'insert').map((d) => d.value).join('')).toBe('de');
    expect(diffs.some((d) => d.op === 'delete')).toBe(false);
  });

  it('中间插入：保留前后 equal 段', () => {
    const diffs = computeDiff('abcdef', 'abcXYZdef');
    const insert = diffs.filter((d) => d.op === 'insert').map((d) => d.value).join('');
    expect(insert).toBe('XYZ');
    // 前后各有 equal 段，说明不是整篇替换
    expect(diffs.filter((d) => d.op === 'equal').map((d) => d.value).join('')).toBe('abcdef');
  });

  it('内容未变：全部 equal，无增删', () => {
    const diffs = computeDiff('same', 'same');
    expect(diffs.every((d) => d.op === 'equal')).toBe(true);
  });

  it('删除中间片段：产生 delete 且长度正确', () => {
    const diffs = computeDiff('abcdef', 'abef');
    expect(diffs.filter((d) => d.op === 'delete').map((d) => d.value).join('')).toBe('cd');
  });
});

describe('双端并发编辑（核心验收）', () => {
  it('A、B 同时在不同位置编辑，合并后双方修改都保留且无重复', () => {
    const base = 'Line1\nLine2\nLine3';
    const { a, b } = createPair(base);

    // A 在**开头**插入一段
    editByDiff(a.text, base, 'A-HEAD\n' + base);
    // B 在**末尾**追加一段（与 A 同时，互不知情）
    editByDiff(b.text, base, base + '\nB-TAIL');

    sync(a.doc, b.doc);

    const finalA = a.text.toString();
    const finalB = b.text.toString();

    // 收敛：两端一致
    expect(finalA).toBe(finalB);
    // 双方修改都在
    expect(finalA).toContain('A-HEAD');
    expect(finalA).toContain('B-TAIL');
    // 无重复（基线内容只出现一次）
    const occurrences = finalA.split('Line2').length - 1;
    expect(occurrences).toBe(1);
  });

  it('A 插入 + B 删除不同片段，合并后 A 的插入仍在', () => {
    const base = 'abcdef';
    const { a, b } = createPair(base);

    editByDiff(a.text, base, 'abcXdef'); // A 在中间插入 X
    editByDiff(b.text, base, 'abef'); // B 删除 cd

    sync(a.doc, b.doc);

    expect(a.text.toString()).toContain('X');
    expect(b.text.toString()).toContain('X');
    expect(a.text.toString()).toBe(b.text.toString());
  });

  it('高频连续编辑（10 次增量）后双端仍收敛一致', () => {
    const { a, b } = createPair('start');

    for (let i = 0; i < 10; i++) {
      const cur = a.text.toString();
      editByDiff(a.text, cur, cur + `[a${i}]`);
      const curB = b.text.toString();
      editByDiff(b.text, curB, curB + `[b${i}]`);
      sync(a.doc, b.doc);
    }

    expect(a.text.toString()).toBe(b.text.toString());
    for (let i = 0; i < 10; i++) {
      expect(a.text.toString()).toContain(`[a${i}]`);
      expect(a.text.toString()).toContain(`[b${i}]`);
    }
  });
});

describe('反向验证：整篇替换确实有害（证明上面用例有约束力）', () => {
  it('双方均整篇替换时，合并后出现重复内容（字符级 diff 不会）', () => {
    const base = 'abcdef';

    // —— 整篇替换路径 ——
    const r = createPair(base);
    editByReplaceAll(r.a.text, base + '-A');
    editByReplaceAll(r.b.text, base + '-B');
    sync(r.a.doc, r.b.doc);
    const replaced = r.a.text.toString();

    // —— 字符级 diff 路径 ——
    const d = createPair(base);
    editByDiff(d.a.text, base, base + '-A');
    editByDiff(d.b.text, base, base + '-B');
    sync(d.a.doc, d.b.doc);
    const diffed = d.a.text.toString();

    // 字符级：基线只出现一次（正确合并）
    expect(diffed.split('abcdef').length - 1).toBe(1);
    expect(diffed).toContain('-A');
    expect(diffed).toContain('-B');

    // 整篇替换：产生重复基线（这就是它必须被替换掉的原因）
    expect(replaced.split('abcdef').length - 1).toBeGreaterThan(1);
  });
});
