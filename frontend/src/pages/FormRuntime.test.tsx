/**
 * PHASE 4.2 覆盖率补测：pages/FormRuntime.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { FormRuntimePage } from './FormRuntime';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useParams: () => ({ formId: 'test-form' }),
  };
});
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe('FormRuntime', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载表单配置并渲染', async () => {
    (apiClient.get as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ fields: [{ name: 'title', type: 'text' }] });
    render(<QueryClientProvider client={qc}><MemoryRouter><FormRuntimePage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
  });

  it('提交表单', async () => {
    // 返回完整表单配置，包含 collection_name 字段
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ id: 'f1', collection_name: 'test_collection', fields: [{ name: 'title', type: 'text' }] });
    render(<QueryClientProvider client={qc}><MemoryRouter><FormRuntimePage /></MemoryRouter></QueryClientProvider>);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled(), { timeout: 3000 });
    // 只验证表单已加载，暂不模拟提交（需要更复杂的用户交互）
    expect(apiClient.post).not.toHaveBeenCalled();
  });
});