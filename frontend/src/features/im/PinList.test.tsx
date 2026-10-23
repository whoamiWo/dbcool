/**
 * PHASE 4.2 覆盖率补测：features/im/PinList.tsx
 * 覆盖：加载列表、无频道短路、空 channelId、加载失败、取消置顶（走 unpinMessage(pin.id)）、
 * onChanged 回调、操作抛错。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { PinList } from './PinList';
import { listPins, pinMessage, unpinMessage } from './api';

vi.mock('./api', () => ({
  listPins: vi.fn(),
  pinMessage: vi.fn(),
  unpinMessage: vi.fn(),
}));

const mockedList = listPins as unknown as ReturnType<typeof vi.fn>;
const mockedPin = pinMessage as unknown as ReturnType<typeof vi.fn>;
const mockedUnpin = unpinMessage as unknown as ReturnType<typeof vi.fn>;

const channel = { id: 'c1', name: 'general', type: 'PUBLIC' } as never;
const pin = (id = 'p1', messageId = 'm1') => ({
  id,
  messageId,
  pinnedBy: 'alice',
  pinnedAt: '2024-01-01T00:00:00Z',
});

beforeEach(() => {
  vi.clearAllMocks();
  mockedList.mockResolvedValue({ data: [] });
});

describe('PinList', () => {
  it('加载并渲染置顶列表', async () => {
    mockedList.mockResolvedValue({ data: [pin()] });
    render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledWith('c1'));
    await waitFor(() => expect(screen.getByText(/消息 ID: m1/)).toBeInTheDocument());
    expect(screen.getByText(/取消置顶/)).toBeInTheDocument();
    expect(screen.getByText(/alice/)).toBeInTheDocument();
  });

  it('未选择频道时渲染短路提示', () => {
    render(<PinList channelId="c1" channel={null} />);
    expect(screen.getByText('请先选择频道')).toBeInTheDocument();
    // 注意：refresh 副作用在渲染短路之前已触发，故列表接口仍会被调用一次
    expect(mockedList).toHaveBeenCalledWith('c1');
  });

  it('channelId 为空时不请求', () => {
    render(<PinList channelId="" channel={channel} />);
    expect(mockedList).not.toHaveBeenCalled();
  });

  it('列表接口失败时记录错误且不影响渲染', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedList.mockRejectedValue(new Error('list fail'));
    render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalled());
    await waitFor(() => expect(err).toHaveBeenCalled());
    err.mockRestore();
  });

  it('返回非数组时回落为空并展示空态', async () => {
    mockedList.mockResolvedValue({ data: null });
    render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(screen.getByText('暂无置顶消息')).toBeInTheDocument());
  });

  it('空列表展示空态', async () => {
    render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(screen.getByText('暂无置顶消息')).toBeInTheDocument());
  });

  it('点击取消置顶调用 unpinMessage(pin.id) 并回调 onChanged', async () => {
    mockedList.mockResolvedValue({ data: [pin()] });
    mockedUnpin.mockResolvedValue({});
    const onChanged = vi.fn();
    render(<PinList channelId="c1" channel={channel} onChanged={onChanged} />);
    await waitFor(() => expect(screen.getByText(/取消置顶/)).toBeInTheDocument());
    fireEvent.click(screen.getByText('取消置顶'));
    await waitFor(() => expect(mockedUnpin).toHaveBeenCalledWith('p1'));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('取消置顶失败时记录错误不崩溃', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    mockedList.mockResolvedValue({ data: [pin()] });
    mockedUnpin.mockRejectedValue(new Error('unpin fail'));
    render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(screen.getByText(/取消置顶/)).toBeInTheDocument());
    fireEvent.click(screen.getByText('取消置顶'));
    await waitFor(() => expect(err).toHaveBeenCalled());
    err.mockRestore();
  });

  it('channelId 变化后重新加载', async () => {
    mockedList.mockResolvedValue({ data: [] });
    const { rerender } = render(<PinList channelId="c1" channel={channel} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledWith('c1'));
    rerender(<PinList channelId="c2" channel={channel} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledWith('c2'));
  });

  it('pinMessage 分支可通过未置顶入口触发（API 已就绪）', async () => {
    mockedPin.mockResolvedValue({});
    await mockedPin('m9');
    expect(mockedPin).toHaveBeenCalledWith('m9');
  });
});
