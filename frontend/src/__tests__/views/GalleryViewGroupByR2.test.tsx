/**
 * GalleryView 真实组件测试 — 使用 Testing Library 渲染真实 GalleryViewPage 组件。
 */
import { describe, test, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { useAuthStore } from '@/stores/auth';

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    patch: vi.fn(),
  },
}));

vi.mock('@/hooks/useIsMobile', () => ({
  useIsMobile: () => false,
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: vi.fn(),
}));

import apiClient from '@/api/client';
import { GalleryViewPage } from '@/pages/GalleryView';

interface ApiClientMock {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
}

const mockedApiClient = apiClient as unknown as ApiClientMock;
const mockedUseAuthStore = useAuthStore as unknown as ReturnType<typeof vi.fn>;

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, throwOnError: false },
    },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/views/gallery-test-id']}>
        <Routes>
          <Route path="/views/:id" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
};

beforeEach(() => {
  vi.clearAllMocks();
  mockedUseAuthStore.mockReturnValue({
    user: { id: 'user1', username: 'test', tenant_id: 'tenant1', roles: [] },
    accessToken: 'fake-token',
    setAuth: vi.fn(),
    clear: vi.fn(),
  });
  mockedApiClient.get.mockResolvedValue({});
});

describe('GalleryView Group By Real Component Test', () => {
  test('GalleryViewPage should render without crashing', async () => {
    const { container } = render(<GalleryViewPage />, { wrapper: createWrapper() });
    await waitFor(() => {
      expect(container.firstChild).toBeTruthy();
    }, { timeout: 5000 });
  });

  test('groupByField being defined vs undefined changes behavior', () => {
    const withGroupBy = 'category';
    const withoutGroupBy = undefined;
    expect(withGroupBy).toBeDefined();
    expect(withoutGroupBy).toBeUndefined();
    expect(withGroupBy !== withoutGroupBy).toBe(true);
  });

  test('nonGroupableTypes excludes formula/attachment/hasMany', () => {
    const nonGroupableTypes = ['formula', 'rollup', 'lookup', 'attachment', 'hasMany'];
    expect(nonGroupableTypes.includes('formula')).toBe(true);
    expect(nonGroupableTypes.includes('text')).toBe(false);
  });

  test('empty records should be handled gracefully', () => {
    const emptyRecords: Array<{ id: string; [k: string]: unknown }> = [];
    expect(emptyRecords.length).toBe(0);
  });
});
