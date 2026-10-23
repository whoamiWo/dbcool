/**
 * PHASE 4.2 覆盖率补测：features/im/MessageComposer.tsx
 * 覆盖：发送消息（按钮/Enter）、空内容不发送、disabled 占位符、发送失败 alert、
 * 斜杠命令面板（输入 "/" 打开、选择命令）、附件选择与超大文件拦截（同步 alert）、
 * 拖拽上传。断言使用 aria-label（i18n key），避免脆弱中文匹配。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MessageComposer } from './MessageComposer';

/** onSent 为必填 prop，未提供交互断言的用例传此空实现。 */
const noopSent = () => {};
import { sendMessage, uploadAttachment, listSlashCommands } from './api';

vi.mock('./api', () => ({
  sendMessage: vi.fn(),
  uploadAttachment: vi.fn(),
  listSlashCommands: vi.fn(),
}));

vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockedSend = sendMessage as unknown as ReturnType<typeof vi.fn>;
const mockedUpload = uploadAttachment as unknown as ReturnType<typeof vi.fn>;
const mockedCmds = listSlashCommands as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedCmds.mockResolvedValue({ code: 0, data: [{ name: 'remind', description: '设置提醒' }] });
  mockedSend.mockResolvedValue({ data: { id: 'm1' } });
  mockedUpload.mockResolvedValue({ code: 0, data: { url: 'https://cdn/a.txt', id: 'a1' } });
});

describe('MessageComposer', () => {
  it('渲染输入框与发送按钮', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    await waitFor(() => expect(screen.getByLabelText('im.inputMessage')).toBeInTheDocument());
    expect(screen.getByLabelText('im.sendMessage')).toBeInTheDocument();
  });

  it('挂载后加载斜杠命令', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    await waitFor(() => expect(mockedCmds).toHaveBeenCalled());
  });

  it('点击发送调用 sendMessage 并清空输入', async () => {
    const onSent = vi.fn();
    render(<MessageComposer channelId="c1" onSent={onSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: 'hello' } });
    fireEvent.click(screen.getByLabelText('im.sendMessage'));
    await waitFor(() => expect(mockedSend).toHaveBeenCalledWith('c1', 'hello', 'TEXT'));
    await waitFor(() => expect(onSent).toHaveBeenCalled());
    expect((input as HTMLTextAreaElement).value).toBe('');
  });

  it('Enter 发送、Shift+Enter 不发送', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: 'enter send' } });
    fireEvent.keyDown(input, { key: 'Enter', shiftKey: true });
    expect(mockedSend).not.toHaveBeenCalled();
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(mockedSend).toHaveBeenCalledWith('c1', 'enter send', 'TEXT'));
  });

  it('空白内容不发送', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: '   ' } });
    fireEvent.click(screen.getByLabelText('im.sendMessage'));
    expect(mockedSend).not.toHaveBeenCalled();
  });

  it('disabled 时展示占位符且不发送', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} disabled />);
    const input = await screen.findByLabelText('im.inputMessage');
    expect(input).toHaveAttribute('placeholder', 'im.selectChannelPlaceholder');
    fireEvent.change(input, { target: { value: 'x' } });
    fireEvent.click(screen.getByLabelText('im.sendMessage'));
    expect(mockedSend).not.toHaveBeenCalled();
  });

  it('发送失败时 alert im.sendFailed', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    mockedSend.mockRejectedValue(new Error('send fail'));
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: 'boom' } });
    fireEvent.click(screen.getByLabelText('im.sendMessage'));
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('im.sendFailed'));
  });

  it('输入 "/" 打开斜杠命令面板', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: '/' } });
    await waitFor(() => expect(screen.getByText('remind')).toBeInTheDocument());
  });

  it('选择斜杠命令写入内容并关闭面板', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const input = await screen.findByLabelText('im.inputMessage');
    fireEvent.change(input, { target: { value: '/' } });
    await waitFor(() => expect(screen.getByText('remind')).toBeInTheDocument());
    fireEvent.click(screen.getByText('remind'));
    await waitFor(() => expect((input as HTMLTextAreaElement).value).toBe('/remind '));
  });

  it('点击附件按钮弹出文件选择器', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const fileBtn = screen.getByLabelText('im.uploadAttachment');
    expect(fileBtn).toBeInTheDocument();
    fileBtn.click();
    expect(document.querySelector('input[type="file"]')).toBeTruthy();
  });

  it('选择文件触发上传并回调 onAttachment', async () => {
    const onAttachment = vi.fn();
    render(<MessageComposer channelId="c1" onSent={noopSent} onAttachment={onAttachment} />);
    await screen.findByLabelText('im.inputMessage');
    // 找到隐藏的 file input
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(fileInput).toBeTruthy();
    const file = new File(['x'], 'a.txt', { type: 'text/plain' });
    fireEvent.change(fileInput, { target: { files: [file] } });
    // uploadAttachment 返回 Promise，需要等待 resolve
    await waitFor(() => expect(mockedUpload).toHaveBeenCalledWith(file));
    await waitFor(() => expect(onAttachment).toHaveBeenCalled());
  });

  it('超大文件（本地检测）触发 alert，不上传', async () => {
    // File.size 为只读，用 defineProperty 在实例上覆盖为 60MB（避免分配 60MB 内存）
    const file = new File(['x'], 'big.txt', { type: 'text/plain' });
    Object.defineProperty(file, 'size', { value: 60 * 1024 * 1024, configurable: true });
    expect(file.size).toBeGreaterThan(50 * 1024 * 1024);
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    await screen.findByLabelText('im.inputAreaHint');
    const panel = document.querySelector('[aria-label="im.inputAreaHint"]')!;
    fireEvent.drop(panel, { dataTransfer: { files: [file] } });
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('im.fileTooLarge'));
    expect(mockedUpload).not.toHaveBeenCalled();
  });

  it('拖拽文件触发上传', async () => {
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    const panel = await screen.findByLabelText('im.inputAreaHint');
    const file = new File(['x'], 'drag.txt', { type: 'text/plain' });
    fireEvent.drop(panel, { dataTransfer: { files: [file] } });
    await waitFor(() => expect(mockedUpload).toHaveBeenCalled());
  });

  it('上传失败时 alert im.uploadFailed', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    mockedUpload.mockRejectedValue(new Error('up fail'));
    render(<MessageComposer channelId="c1" onSent={noopSent} />);
    await screen.findByLabelText('im.inputAreaHint');
    const panel = document.querySelector('[aria-label="im.inputAreaHint"]')!;
    const file = new File(['x'], 'fail.txt', { type: 'text/plain' });
    fireEvent.drop(panel, { dataTransfer: { files: [file] } });
    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('im.uploadFailed'));
  });
});
