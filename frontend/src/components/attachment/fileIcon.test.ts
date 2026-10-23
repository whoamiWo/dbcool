/**
 * PHASE 4.2 覆盖率补测：components/attachment/fileIcon.ts
 * 纯函数层：文件分类（ext/category/icon/label）、图片判断、大小格式化、文件名解码。
 */
import { describe, it, expect } from 'vitest';
import {
  getFileInfo,
  isImage,
  formatFileSize,
  decodeFilename,
  getFileExtension,
} from './fileIcon';

describe('getFileInfo', () => {
  it('识别图片类型', () => {
    expect(getFileInfo('photo.PNG').category).toBe('image');
    expect(getFileInfo('a.jpeg').category).toBe('image');
    expect(getFileInfo('b.gif').category).toBe('image');
    expect(getFileInfo('c.webp').category).toBe('image');
    expect(getFileInfo('d.svg').category).toBe('image');
    expect(getFileInfo('photo.png').icon).toBe('file-icon-image');
    expect(getFileInfo('photo.png').label).toBe('图片');
  });

  it('识别文档类型', () => {
    expect(getFileInfo('report.pdf').category).toBe('doc');
    expect(getFileInfo('notes.txt').category).toBe('doc');
    expect(getFileInfo('sheet.xlsx').category).toBe('doc');
    expect(getFileInfo('deck.pptx').category).toBe('doc');
    expect(getFileInfo('data.csv').category).toBe('doc');
    expect(getFileInfo('report.pdf').label).toBe('文档');
  });

  it('识别压缩包类型', () => {
    expect(getFileInfo('bundle.zip').category).toBe('archive');
    expect(getFileInfo('pkg.gz').category).toBe('archive');
    expect(getFileInfo('rar.rar').category).toBe('archive');
    expect(getFileInfo('a.7z').category).toBe('archive');
    expect(getFileInfo('bundle.zip').label).toBe('压缩包');
  });

  it('未知类型归为 other', () => {
    expect(getFileInfo('mystery.xyz').category).toBe('other');
    expect(getFileInfo('mystery.xyz').label).toBe('文件');
  });

  it('ext 为小写扩展名', () => {
    expect(getFileInfo('A.PNG').ext).toBe('png');
    expect(getFileInfo('B.ZIP').ext).toBe('zip');
  });

  it('无扩展名时 pop 返回整个文件名，归类为 other', () => {
    expect(getFileInfo('noext').ext).toBe('noext');
    expect(getFileInfo('noext').category).toBe('other');
  });
});

describe('isImage', () => {
  it('图片返回 true，非图片返回 false', () => {
    expect(isImage('a.png')).toBe(true);
    expect(isImage('a.jpg')).toBe(true);
    expect(isImage('a.pdf')).toBe(false);
    expect(isImage('a.zip')).toBe(false);
  });
});

describe('formatFileSize', () => {
  it('非数字或 <=0 返回空串', () => {
    expect(formatFileSize(null)).toBe('');
    expect(formatFileSize(undefined)).toBe('');
    expect(formatFileSize('')).toBe('');
    expect(formatFileSize('abc')).toBe('');
    expect(formatFileSize(0)).toBe('');
    expect(formatFileSize(-1)).toBe('');
  });

  it('小于 1KB 显示为 B（无小数）', () => {
    expect(formatFileSize(512)).toBe('512 B');
  });

  it('KB / MB / GB 逐级换算（保留 1 位小数）', () => {
    expect(formatFileSize(1024)).toBe('1.0 KB');
    expect(formatFileSize(1024 * 1024)).toBe('1.0 MB');
    expect(formatFileSize(1024 * 1024 * 1024)).toBe('1.0 GB');
  });

  it('非 1024 整数倍按实际值格式化', () => {
    expect(formatFileSize(1536)).toBe('1.5 KB');
  });
});

describe('decodeFilename', () => {
  it('从 storageKey 中取最后一段作为文件名', () => {
    expect(decodeFilename('a/b/c/report.pdf')).toBe('report.pdf');
    expect(decodeFilename('single.txt')).toBe('single.txt');
  });
});

describe('getFileExtension', () => {
  it('返回大写扩展名', () => {
    expect(getFileExtension('A.PNG')).toBe('PNG');
    expect(getFileExtension('a.b.pdf')).toBe('PDF');
  });

  it('无扩展名返回空串', () => {
    expect(getFileExtension('noext')).toBe('');
  });
});
