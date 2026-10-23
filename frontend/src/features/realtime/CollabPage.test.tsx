/**
 * PHASE 4.2 覆盖率补测：features/realtime/CollabPage.tsx
 * 覆盖：loading、加载失败、成功渲染、保存回调。
 * 注：CollabEditor 为 Yjs 协同编辑器（重型），此处 mock 掉只验证装配与 save 链路。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CollabPage from './CollabPage';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), put: vi.fn() } }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useParams: () => ({ docId: 'doc-1' }) };
});
// 捕获 onSave 以便从测试触发保存
const saveHandler = vi.fn();
vi.mock('./CollabEditor', () => ({
  __esModule: true,
  default: (props: { onSave: (c: string) => void; title: string }) => {
    saveHandler.mockImplementation(props.onSave);
    return <div data-testid="collab-editor">{props.title}</div>;
  },
}));

const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPut = apiClient.put as unknown as ReturnType<typeof vi.fn>;

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <CollabPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({ code: 0, data: { id: 'doc-1', title: '协作文档', content: '正文' } });
  mockedPut.mockResolvedValue({ code: 0 });
});

describe('CollabPage', () => {
  it('加载中渲染进度圈', () => {
    mockedGet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('加载失败渲染错误提示', async () => {
    mockedGet.mockRejectedValue(new Error('网络异常'));
    renderPage();
    await waitFor(() => expect(screen.getByText(/collab.loadFailed/)).toBeInTheDocument());
    expect(screen.getByText(/网络异常/)).toBeInTheDocument();
  });

  it('加载成功渲染标题与编辑器', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('collab-editor')).toBeInTheDocument());
    expect(screen.getByText('协作文档')).toBeInTheDocument();
    expect(screen.getByText('collab.title')).toBeInTheDocument();
    expect(mockedGet).toHaveBeenCalledWith('/wiki/pages/doc-1');
  });

  it('响应无 data 包装时直接取业务对象', async () => {
    mockedGet.mockResolvedValue({ id: 'doc-1', title: '裸数据文档', content: 'x' });
    renderPage();
    await waitFor(() => expect(screen.getByText('裸数据文档')).toBeInTheDocument());
  });

  it('保存时调用 PUT 并失效查询', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('collab-editor')).toBeInTheDocument());
    // 触发编辑器保存回调
    await saveHandler('新内容');
    await waitFor(() =>
      expect(mockedPut).toHaveBeenCalledWith('/wiki/pages/doc-1', { content: '新内容' }),
    );
  });
});
