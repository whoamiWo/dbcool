/**
 * 暗色主题样式替换脚本
 * 
 * 作用：将硬编码亮色替换为 CSS 变量
 * 模式：
 *   '#f1f5f9' → 'var(--color-bg-secondary)'
 *   '#e2e8f0' → 'var(--color-border-light)'
 *   '#f5f5f5' → 'var(--glass-bg-light)'
 *   '#fee2e2' → 'rgba(239,68,68,0.2)'
 *   '#e0e7ff' → 'rgba(99,102,241,0.2)'
 *   '#d1d5db' → 'var(--color-border-medium)'
 *   '#e5e7eb' → 'var(--color-border-light)'
 *   '#f3f4f6' → 'var(--color-bg-tertiary)'
 *   '#ecfeff' → 'rgba(8,145,178,0.1)'
 *   '#eff6ff' → 'rgba(59,130,246,0.1)'
 *   '#fef9c3' → 'rgba(245,158,11,0.1)'
 *   '#f8fafc' → 'var(--color-bg-secondary)'
 *   '#fff' (作为背景) → 'var(--glass-bg-light)'
 */

import fs from 'fs';
import path from 'path';
import { glob } from 'glob';

const replacements: Array<[RegExp, string]> = [
  [/background:\s*'#[fFeEdD][0-9a-fA-F]{5}'/g, (m: string) => m.replace(/#[fFeEdD][0-9a-fA-F]{5}/, 'var(--color-bg-secondary)')],
  [/border.*#[eEdD][0-9a-fA-F]{5}/g, (m: string) => m.replace(/#[eEdD][0-9a-fA-F]{5}/, 'var(--color-border-light)')],
  [/background:\s*'#[fF]5f5f5'/g, 'background: var(--glass-bg-light)'],
  [/background:\s*'#[fF]e[eE]2[eE]2'/g, 'background: rgba(239,68,68,0.2)'],
  [/background:\s*'#[eE]0[eE]7ff'/g, 'background: rgba(99,102,241,0.2)'],
  [/background:\s*'#[eEcE]fe[ccf][fF]'/g, 'background: rgba(8,145,178,0.1)'],
  [/background:\s*'#[eEfE]ff6bf'/g, 'background: rgba(59,130,246,0.1)'],
  [/background:\s*'#[fF]ef9[cC]3'/g, 'background: rgba(245,158,11,0.1)'],
  [/background:\s*'#[fF]8[fF][aA][fF]c'/g, 'background: var(--color-bg-secondary)'],
  [/background:\s*'#[fF]3[fF]4[fF]6'/g, 'background: var(--color-bg-tertiary)'],
];

function processFile(filePath: string) {
  let content = fs.readFileSync(filePath, 'utf-8');
  let modified = false;

  for (const [pattern, replacement] of replacements) {
    const newContent = content.replace(pattern, replacement as any);
    if (newContent !== content) {
      modified = true;
      content = newContent;
    }
  }

  if (modified) {
    fs.writeFileSync(filePath, content, 'utf-8');
    console.log(`  Fixed: ${filePath}`);
  }
}

async function main() {
  const srcDir = path.resolve(__dirname, '../src');
  const files = await glob('**/*.{tsx,ts}', { cwd: srcDir, ignore: ['**/node_modules/**', '**/test/**', '**/*.test.tsx', '**/*.spec.tsx'] });

  let fixed = 0;
  for (const file of files) {
    const filePath = path.join(srcDir, file);
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      // Check for light colors
      if (content.includes('#f1f5f9') || content.includes('#e2e8f0') ||
          content.includes('#f5f5f5') || content.includes('#fee2e2') ||
          content.includes('#e0e7ff') || content.includes('#ecfeff') ||
          content.includes('#eff6ff') || content.includes('#fef9c3') ||
          content.includes('#f8fafc') || content.includes('#f3f4f6') ||
          content.includes('#d1d5db') || content.includes('#e5e7eb')) {
        processFile(filePath);
        fixed++;
      }
    } catch (e) {
      // skip
    }
  }
  console.log(`\nFixed ${fixed} files with dark theme color replacements.`);
}

main().catch(console.error);