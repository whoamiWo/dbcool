/**
 * PHASE 4.2 覆盖率补测：components/attachment/AttachmentLightbox.tsx
 * 覆盖：未打开不渲染、打开后标题取文件名、下载（axios blob + createObjectURL）、
 * 下载失败、关闭回调重置状态。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import AttachmentLightbox from './AttachmentLightbox';

const mockedAxiosGet = vi.fn();
vi.mock('axios', () => ({
  default: { get: (...args: unknown[]) => mockedAxiosGet(...args) },
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k }),
}));

const createObjectURL = vi.fn(() => 'blob:mock');
const revokeObjectURL = vi.fn();
Object.assign(URL, { createObjectURL, revokeObjectURL });

beforeEach(() => {
  vi.clearAllMocks();
  createObjectURL.mockReturnValue('blob:mock');
});

describe('AttachmentLightbox', () => {
  it('open=false 时不渲染对话框内容', () => {
    render(<AttachmentLightbox open={false} onClose={vi.fn()} storageKey="a/b/report.pdf" />);
    expect(screen.queryByText('report.pdf')).not.toBeInTheDocument();
  });

  it('打开后展示文件名（storageKey 末段）', async () => {
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="a/b/report.pdf" />);
    await waitFor(() => expect(screen.getByText('report.pdf')).toBeInTheDocument());
  });

  it('无 storageKey 时展示未知文件', async () => {
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="" />);
    await waitFor(() => expect(screen.getByText('未知文件')).toBeInTheDocument());
  });

  it('点击下载调用预览接口并触发 a 标签下载', async () => {
    mockedAxiosGet.mockResolvedValue({ data: new Blob(['x']) });
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="dir/file.txt" />);
    await waitFor(() => expect(screen.getByText('file.txt')).toBeInTheDocument());

    fireEvent.click(screen.getByText('下载原图'));

    await waitFor(() => expect(mockedAxiosGet).toHaveBeenCalled());
    const [url, config] = mockedAxiosGet.mock.calls[0] as [string, { responseType: string }];
    expect(url).toContain('/api/attachments/preview');
    expect(url).toContain(encodeURIComponent('dir/file.txt'));
    expect(config.responseType).toBe('blob');
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled());
    clickSpy.mockRestore();
  });

  it('下载失败时记录错误且不崩溃', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedAxiosGet.mockRejectedValue(new Error('dl fail'));
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="dir/file.txt" />);
    await waitFor(() => expect(screen.getByText('file.txt')).toBeInTheDocument());
    fireEvent.click(screen.getByText('下载原图'));
    await waitFor(() => expect(err).toHaveBeenCalled());
    err.mockRestore();
  });

  it('点击底部关闭按钮触发 onClose', async () => {
    const onClose = vi.fn();
    render(<AttachmentLightbox open onClose={onClose} storageKey="a/b.pdf" />);
    await waitFor(() => expect(screen.getByText('b.pdf')).toBeInTheDocument());
    fireEvent.click(screen.getByText('关闭'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('点击右上角带 aria-label 的关闭按钮触发 onClose', async () => {
    const onClose = vi.fn();
    render(<AttachmentLightbox open onClose={onClose} storageKey="a/b.pdf" />);
    await waitFor(() => expect(screen.getByText('b.pdf')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('common.close'));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('图片加载失败展示错误态', async () => {
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="a/b.pdf" />);
    await waitFor(() => expect(screen.getByText('b.pdf')).toBeInTheDocument());
    const img = document.querySelector('img');
    expect(img).toBeTruthy();
    fireEvent.error(img!);
    await waitFor(() => expect(screen.getByText('预览加载失败')).toBeInTheDocument());
  });

  it('图片加载完成隐藏 loading', async () => {
    render(<AttachmentLightbox open onClose={vi.fn()} storageKey="a/b.pdf" />);
    await waitFor(() => expect(screen.getByText('b.pdf')).toBeInTheDocument());
    const img = document.querySelector('img');
    fireEvent.load(img!);
    await waitFor(() => expect(screen.queryByText('加载中...')).not.toBeInTheDocument());
  });
});
