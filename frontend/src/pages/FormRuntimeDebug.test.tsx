import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { FormRuntimePage } from './FormRuntime';
import apiClient from '@/api/client';

vi.mock('@/api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({ formId: 'test-form' }) };
});
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));
vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));

describe('FormRuntime Debug', () => {
  beforeEach(() => vi.clearAllMocks());

  it('wait for title', async () => {
    const getMock = apiClient.get as any;
    getMock
      .mockResolvedValueOnce({ code: 0, data: { id: 'f1', collection_name: 'users', title: '用户表单', layout_json: '[]', rules_json: '{}' } })
      .mockResolvedValueOnce({ code: 0, data: { fields: [] } });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    // Wait for title to appear instead of counting API calls
    await waitFor(() => {
      expect(screen.getByText('用户表单')).toBeInTheDocument();
    }, { timeout: 5000 });

    expect(getMock).toHaveBeenCalledTimes(2);
  });
});
