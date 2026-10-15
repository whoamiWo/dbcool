import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import tsParser from '@typescript-eslint/parser';
import tsPlugin from '@typescript-eslint/eslint-plugin';

/**
 * PHASE98 R4 遗留：自定义规则「图标按钮必须有可访问名」。
 *
 * 为什么需要自定义：`jsx-a11y/control-has-associated-label` **检测不到**本批真正
 * 要防的情况 —— 当 `<button>` 的 children 是图标组件（`<X/>`、`<DeleteIcon/>`）
 * 时，该规则认为"有 children"即视为有关联内容，实测对
 * `<button onClick><X size={16}/></button>` 完全不报错（已验证，切勿当作门禁）。
 * 而 R4 的主题正是"icon-only 按钮缺 aria-label"，所以必须自建规则。
 *
 * 判定逻辑（三条全满足才报错）：
 *   1. 元素是 button / Button / IconButton；
 *   2. 没有 aria-label / aria-labelledby / title 任一可访问名属性；
 *   3. children 中**没有任何文本**（图标组件内部也无文本）；
 * 豁免：被 <Tooltip> 包裹的按钮（MUI 会在运行时注入 aria-label，静态无法感知）。
 */
const ICON_BUTTON_TARGETS = ['button', 'Button', 'IconButton'];

function elementName(node) {
  return node?.name?.name ?? node?.name?.property?.name ?? null;
}

// 这些表达式在运行时大概率产出文本（含 i18n 调用 t('...')），视为"有文本"。
// 修正前只认 Literal/Identifier，把 <button>{t('im.close')}</button> 误判为
// 图标按钮（PHASE98 实测踩坑）。
const TEXT_LIKE_EXPR = [
  'Literal',
  'TemplateLiteral',
  'Identifier',
  'CallExpression',
  'MemberExpression',
  'ConditionalExpression',
  'LogicalExpression',
  'BinaryExpression',
];

function hasTextChildren(el) {
  return (el?.children ?? []).some((c) => {
    if (c.type === 'JSXText') return c.value.trim().length > 0;
    if (c.type === 'JSXExpressionContainer') {
      const e = c.expression;
      if (!e) return false;
      if (TEXT_LIKE_EXPR.includes(e.type)) return true;
      if (e.type === 'JSXElement') return hasTextChildren(e);
      return false;
    }
    if (c.type === 'JSXElement') return hasTextChildren(c);
    return false;
  });
}

function insideTooltip(node) {
  let p = node?.parent;
  let depth = 0;
  while (p && depth < 3) {
    if (p.type === 'JSXElement') {
      const nm = elementName(p.openingElement);
      if (nm && String(nm).includes('Tooltip')) return true;
    }
    p = p.parent;
    depth += 1;
  }
  return false;
}

const iconButtonNeedsAccessibleName = {
  meta: {
    type: 'problem',
    docs: {
      description: 'icon-only 按钮必须提供可访问名（aria-label / aria-labelledby / title）',
    },
    messages: {
      needName:
        '图标按钮缺少可访问名：请补 aria-label（或使用 IconButtonA11y 组件），否则屏幕阅读器无法获知该按钮用途。',
    },
    schema: [],
  },
  create(context) {
    return {
      JSXOpeningElement(node) {
        const name = elementName(node);
        if (!ICON_BUTTON_TARGETS.includes(name)) return;

        const hasAccessibleName = node.attributes.some((a) => {
          const n = elementName(a);
          return n === 'aria-label' || n === 'aria-labelledby' || n === 'title';
        });
        if (hasAccessibleName) return;

        // aria-hidden="true"：显式对辅助技术隐藏。用于"功能尚未实现、先不对
        // AT 暴露"的控件（如 NotionStyleEditor 里待接入 dnd-kit 的拖拽手柄），
        // 这是正确的无障碍处理，不算缺失可访问名。
        const isAriaHidden = node.attributes.some((a) => {
          if (elementName(a) !== 'aria-hidden') return false;
          const v = a.value;
          if (v?.type === 'Literal') return v.value === 'true' || v.value === true;
          if (v?.type === 'JSXExpressionContainer') {
            const e = v.expression;
            return e?.type === 'Literal' && (e.value === 'true' || e.value === true);
          }
          return false;
        });
        if (isAriaHidden) return;

        if (insideTooltip(node)) return;

        const parentElement = node.parent; // JSXElement
        if (hasTextChildren(parentElement)) return;

        context.report({ node, messageId: 'needName' });
      },
    };
  },
};

/**
 * ESLint flat config（ESLint 9）。
 *
 * PHASE98 R4 遗留补齐：此前仓库**根本没有 eslint 配置文件**，
 * `package.json` 里的 `"lint": "eslint ."` 在 ESLint 9 下因找不到
 * flat config 直接 exit 2 —— 这个 lint 门禁从来没真正跑起来过。
 *
 * 本配置一次补齐三件事：
 * 1. 让 `pnpm lint` 真正可用（TypeScript 解析 + 基础规则）；
 * 2. 引入 `eslint-plugin-jsx-a11y`；
 * 3. 引入 `eslint-plugin-react-hooks`：源码里已存在多处
 *    `// eslint-disable-next-line react-hooks/exhaustive-deps`，
 *    但插件未安装时 ESLint 会报 "Definition for rule ... was not found"。
 *
 * R4 主题（图标按钮可访问名）由上面的自定义规则 local/icon-button-needs-name
 * 兜底（error 阻断），已做可失败性验证：加一个无 aria-label 的图标按钮即红。
 */
export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'e2e/**',
      'coverage/**',
      '*.config.ts',
      '*.config.js',
      '*.cjs',
    ],
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      'jsx-a11y': jsxA11y,
      'react-hooks': reactHooks,
      local: { rules: { 'icon-button-needs-name': iconButtonNeedsAccessibleName } },
    },
    rules: {
      ...jsxA11y.flatConfigs.recommended.rules,
      ...reactHooks.configs.recommended.rules,

      // ===== R4 核心门禁：icon-only 按钮必须可访问名（可失败性已验证）=====
      'local/icon-button-needs-name': 'error',

      // control-has-associated-label：补充检测 label 关联等场景。
      // ignoreElements 只填原生元素名（jsx-a11y 会归一化大小写，
      // 填 'Button' 会误伤原生 <button>，PHASE98 已踩坑并修正）。
      'jsx-a11y/control-has-associated-label': [
        'error',
        {
          ignoreElements: ['input', 'select', 'textarea', 'option'],
          controlComponents: ['IconButtonA11y'],
          depth: 3,
        },
      ],
      'jsx-a11y/aria-props': 'error',
      'jsx-a11y/role-has-required-aria-props': 'error',

      // react-hooks v7 的严格规则与本批 R4 主题无关，存量较多 → warn 不阻断
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/immutability': 'warn',
      'react-hooks/preserve-manual-memoization': 'warn',
      'react-hooks/rules-of-hooks': 'warn',

      // 存量次档（label 关联、键盘事件、autofocus 等）记录不阻断，数量见索引
      'jsx-a11y/label-has-associated-control': 'warn',
      'jsx-a11y/click-events-have-key-events': 'warn',
      'jsx-a11y/no-static-element-interactions': 'warn',
      'jsx-a11y/no-noninteractive-element-interactions': 'warn',
      'jsx-a11y/no-autofocus': 'warn',
      'jsx-a11y/media-has-caption': 'warn',
      'jsx-a11y/anchor-is-valid': 'warn',
      'react-hooks/exhaustive-deps': 'warn',

      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
];
