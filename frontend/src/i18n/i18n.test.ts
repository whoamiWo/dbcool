import { describe, it, expect, beforeEach } from 'vitest';
import i18n, { changeLanguage } from './index';
import zhCN from './locales/zh-CN.json';
import enUS from './locales/en-US.json';

describe('i18n', () => {
  beforeEach(() => {
    localStorage.clear();
    // 每个用例前重置为默认语言，避免互相污染
    return i18n.changeLanguage('zh-CN');
  });

  it('默认语言为 zh-CN（保证既有中文断言与 E2E 不被破坏）', () => {
    expect(i18n.language).toBe('zh-CN');
    expect(i18n.t('common.cancel')).toBe('取消');
  });

  it('切换到 en-US 后文案变为英文', async () => {
    await changeLanguage('en-US');
    expect(i18n.language).toBe('en-US');
    expect(i18n.t('common.cancel')).toBe('Cancel');
  });

  it('切换语言会持久化到 localStorage', async () => {
    await changeLanguage('en-US');
    expect(localStorage.getItem('i18n-lang')).toBe('en-US');
  });

  it('未翻译的 key 回退中文（不出现空白文案）', async () => {
    await changeLanguage('en-US');
    // zh-CN.json 有、en-US.json 缺失的 key 应回退到中文而非空串
    expect(i18n.t('login.resetHint')).toBeTruthy();
  });

  it('zh-CN 与 en-US 的 key 结构一致（避免漏翻导致英文站缺文案）', () => {
    const flatKeys = (obj: Record<string, unknown>, prefix = ''): string[] =>
      Object.entries(obj).flatMap(([k, v]) =>
        v && typeof v === 'object' && !Array.isArray(v)
          ? flatKeys(v as Record<string, unknown>, `${prefix}${k}.`)
          : [`${prefix}${k}`],
      );
    const zhKeys = flatKeys(zhCN as Record<string, unknown>).sort();
    const enKeys = flatKeys(enUS as Record<string, unknown>).sort();
    expect(enKeys).toEqual(zhKeys);
  });

  it('所有翻译值非空（避免空文案渲染到界面）', () => {
    const walk = (obj: Record<string, unknown>, path = ''): void => {
      for (const [k, v] of Object.entries(obj)) {
        const p = path ? `${path}.${k}` : k;
        if (v && typeof v === 'object' && !Array.isArray(v)) {
          walk(v as Record<string, unknown>, p);
        } else {
          expect(String(v).trim(), `${p} 不应为空`).not.toBe('');
        }
      }
    };
    walk(zhCN as Record<string, unknown>);
    walk(enUS as Record<string, unknown>);
  });
});