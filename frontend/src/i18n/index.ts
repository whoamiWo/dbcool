import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zhCN from './locales/zh-CN.json';
import enUS from './locales/en-US.json';

/**
 * i18n 初始化
 *
 * PHASE 56 P2-1：前端国际化
 * - 默认语言 zh-CN（保持 E2E 中文断言不被破坏）
 * - 通过 localStorage 持久化用户选择的语言
 * - 未翻译的 key 回退中文
 */
i18n.use(initReactI18next).init({
  lng: typeof window !== 'undefined' ? (localStorage.getItem('i18n-lang') || 'zh-CN') : 'zh-CN',
  fallbackLng: 'zh-CN',
  resources: {
    'zh-CN': { translation: zhCN },
    'en-US': { translation: enUS },
  },
  interpolation: {
    escapeValue: false,
  },
});

export default i18n;

/** 切换语言并持久化 */
export const changeLanguage = (lang: 'zh-CN' | 'en-US') => {
  localStorage.setItem('i18n-lang', lang);
  return i18n.changeLanguage(lang);
};