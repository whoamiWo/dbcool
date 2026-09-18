import { describe, it, expect, beforeEach, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WikiPageReadPage } from './WikiPageRead';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({
  default: { get: vi.fn() },
}));

describe('WikiPageReadPage', () => {
  let qc: QueryClient;

  beforeEach(() => {
    qc = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
    });
    vi.clearAllMocks();
  });

  const renderPage = (slug: string = 'my-page') =>
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[`/wiki/${slug}`]}>
          <Routes>
            <Route path="/wiki/:slug" element={<WikiPageReadPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

  it('加载页面:显示标题和内容', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: [
        { id: 'p1', title: '我的页面', slug: 'my-page', content: '# Hello World', status: 'PUBLISHED', version: 2 },
      ],
    } as any);
    renderPage();
    const titles = await screen.findAllByText('我的页面');
    expect(titles.length).toBeGreaterThanOrEqual(1);
    expect(await screen.findByText(/Hello World/)).toBeInTheDocument();
  });

  it('显示版本号', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: [{ id: 'p1', title: 'P', slug: 'p', content: 'c', status: 'PUBLISHED', version: 5 }],
    } as any);
    renderPage('p');
    expect(await screen.findByText(/v5/)).toBeInTheDocument();
  });

  it('显示状态徽章', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: [{ id: 'p1', title: 'P', slug: 'p', content: 'c', status: 'PUBLISHED', version: 1 }],
    } as any);
    renderPage('p');
    expect(await screen.findByText('PUBLISHED')).toBeInTheDocument();
  });

  it('页面不存在:显示"页面不存在"', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] } as any);
    renderPage('nonexistent');
    expect(await screen.findByText(/页面不存在/)).toBeInTheDocument();
  });
});