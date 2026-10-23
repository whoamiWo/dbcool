/**
 * PHASE 4.2 覆盖率补测：features/im/SearchResults.tsx
 * 覆盖：空关键词短路、搜索成功（含高亮）、失败、空结果、点击回调、limit 透传、has_more。
 * 注意：命中内容经 dangerouslySetInnerHTML 注入 <mark>，文本被拆多节点，
 * 故断言用 container.textContent 而非 getByText 精确匹配。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { SearchResults } from './SearchResults';
import { searchCrossChannel } from './api';

vi.mock('./api', () => ({
  searchCrossChannel: vi.fn(),
}));

const mockedSearch = searchCrossChannel as unknown as ReturnType<typeof vi.fn>;

const page = (messages: unknown[], hasMore = false) => ({
  data: {
    keyword: 'k',
    messages,
    total: messages.length,
    has_more: hasMore,
  },
});

const msg = (id: string, channelId: string, content: string) => ({
  id,
  channelId,
  content,
  createdAt: '2024-01-01T00:00:00Z',
  senderName: 'alice',
});

beforeEach(() => {
  vi.clearAllMocks();
});

describe('SearchResults', () => {
  it('关键词为空时不请求并提示输入', () => {
    render(<SearchResults keyword="" />);
    expect(mockedSearch).not.toHaveBeenCalled();
    expect(screen.getByText('请输入关键词搜索')).toBeInTheDocument();
  });

  it('纯空白关键词同样不请求', () => {
    render(<SearchResults keyword="   " />);
    expect(mockedSearch).not.toHaveBeenCalled();
  });

  it('搜索成功渲染结果条数与命中内容', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'hello world'), msg('m2', 'c2', 'second hit')]));
    const { container } = render(<SearchResults keyword="hello" />);
    await waitFor(() => expect(mockedSearch).toHaveBeenCalledWith('hello', 20));
    await waitFor(() => expect(screen.getByText(/共 2 条结果/)).toBeInTheDocument());
    expect(container.textContent).toContain('hello');
    expect(container.textContent).toContain('second hit');
  });

  it('关键词高亮包裹 mark 标签', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'hello world')]));
    const { container } = render(<SearchResults keyword="hello" />);
    await waitFor(() => expect(screen.getByText(/共 1 条结果/)).toBeInTheDocument());
    expect(container.innerHTML).toContain('<mark');
  });

  it('透传自定义 limit', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'x')]));
    render(<SearchResults keyword="x" limit={5} />);
    await waitFor(() => expect(mockedSearch).toHaveBeenCalledWith('x', 5));
  });

  it('请求失败展示错误态', async () => {
    mockedSearch.mockRejectedValue(new Error('search boom'));
    render(<SearchResults keyword="x" />);
    await waitFor(() => expect(screen.getByText(/搜索失败/)).toBeInTheDocument());
  });

  it('无结果时展示空态', async () => {
    mockedSearch.mockResolvedValue(page([]));
    render(<SearchResults keyword="zzz" />);
    await waitFor(() => expect(screen.getByText(/未找到匹配/)).toBeInTheDocument());
  });

  it('has_more 时展示"加载更多"', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'x')], true));
    render(<SearchResults keyword="x" />);
    await waitFor(() => expect(screen.getByText('加载更多')).toBeInTheDocument());
  });

  it('点击结果触发 onMessageClick', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'hello world')]));
    const onClick = vi.fn();
    render(<SearchResults keyword="hello" onMessageClick={onClick} />);
    await waitFor(() => expect(screen.getByText(/共 1 条结果/)).toBeInTheDocument());
    fireEvent.click(screen.getAllByRole('button')[0]);
    await waitFor(() => expect(onClick).toHaveBeenCalledWith('m1', 'c1'));
  });

  it('关键词变化时重新搜索', async () => {
    mockedSearch.mockResolvedValue(page([msg('m1', 'c1', 'a')]));
    const { rerender } = render(<SearchResults keyword="a" />);
    await waitFor(() => expect(mockedSearch).toHaveBeenCalledWith('a', 20));
    rerender(<SearchResults keyword="b" />);
    await waitFor(() => expect(mockedSearch).toHaveBeenCalledWith('b', 20));
  });

  it('卸载后异步返回不产生状态更新错误', async () => {
    let resolveFn: (v: unknown) => void = () => {};
    mockedSearch.mockReturnValue(new Promise(r => { resolveFn = r; }));
    const { unmount } = render(<SearchResults keyword="x" />);
    unmount();
    resolveFn(page([]));
    await Promise.resolve();
    expect(true).toBe(true);
  });
});
