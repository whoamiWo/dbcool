/**
 * PHASE 4.2 覆盖率补测：components/SharePanel.tsx
 *
 * 仅覆盖纯函数 serializeShares / parseShares（模块级导出、与组件渲染无关）。
 *
 * ⚠️ 为何不测组件渲染：组件内
 *      useEffect(() => { setEntries(initialShares); }, [initialShares])
 * 的默认参数 `initialShares = []` 每次渲染都是**新数组引用**，
 * 导致 effect 每次都触发 setEntries → 重渲染 → 新数组 → 无限循环，
 * vitest 会挂死。这是组件自身的缺陷（真实业务中父组件通常传稳定引用故不显），
 * 按本阶段"不改业务源码"的范围约束，此处仅测纯函数，
 * 组件渲染路径列为后续修复项（把默认参数提到模块级常量即可解决）。
 */
import { describe, it, expect } from 'vitest';
import { serializeShares, parseShares } from './SharePanel';

describe('serializeShares', () => {
  it('把条目序列化为 "type:name" 数组 JSON', () => {
    expect(serializeShares([{ type: 'user', name: 'alice' }])).toBe('["user:alice"]');
  });

  it('支持 user 与 role 两种类型', () => {
    expect(serializeShares([{ type: 'user', name: 'a' }, { type: 'role', name: 'admin' }])).toBe(
      '["user:a","role:admin"]',
    );
  });

  it('空数组序列化为 []', () => {
    expect(serializeShares([])).toBe('[]');
  });

  it('name 中带冒号也原样拼接', () => {
    expect(serializeShares([{ type: 'user', name: 'a:b' }])).toBe('["user:a:b"]');
  });
});

describe('parseShares', () => {
  it('空值返回空数组', () => {
    expect(parseShares(null)).toEqual([]);
    expect(parseShares(undefined)).toEqual([]);
    expect(parseShares('')).toEqual([]);
  });

  it('解析 user / role 条目', () => {
    expect(parseShares('["user:alice","role:admin"]')).toEqual([
      { type: 'user', name: 'alice' },
      { type: 'role', name: 'admin' },
    ]);
  });

  it('name 中含冒号时只按第一个冒号切分', () => {
    expect(parseShares('["user:a:b"]')).toEqual([{ type: 'user', name: 'a:b' }]);
  });

  it('未知类型回落为 user', () => {
    expect(parseShares('["weird:x"]')).toEqual([{ type: 'user', name: 'x' }]);
  });

  it('role 类型正确识别', () => {
    expect(parseShares('["role:ops"]')).toEqual([{ type: 'role', name: 'ops' }]);
  });

  it('过滤不含冒号的非字符串元素', () => {
    expect(parseShares('["nocolon",123,null]')).toEqual([]);
  });

  it('非数组 JSON 返回空数组', () => {
    expect(parseShares('{"a":1}')).toEqual([]);
  });

  it('非法 JSON 返回空数组', () => {
    expect(parseShares('not-json')).toEqual([]);
  });
});

describe('serialize/parse 互逆', () => {
  it('序列化后再解析得到原条目', () => {
    const entries = [
      { type: 'user' as const, name: 'bob' },
      { type: 'role' as const, name: 'ops' },
    ];
    expect(parseShares(serializeShares(entries))).toEqual(entries);
  });

  it('多条目往返保持顺序与类型', () => {
    const entries = [
      { type: 'role' as const, name: 'r1' },
      { type: 'user' as const, name: 'u1' },
      { type: 'user' as const, name: 'u2' },
    ];
    expect(parseShares(serializeShares(entries))).toEqual(entries);
  });
});
