/**
 * PHASE 4.2 覆盖率补测：components/AppLayout.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from './AppLayout';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { id: 'u1', username: 'alice', roles: ['admin'] },
    clear: vi.fn(),
    switchTenant: vi.fn(),
  }),
}));

vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/lib/stompClient', () => ({ disconnectStomp: vi.fn() }));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AppLayout', () => {
  function renderView() {
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>
    );
  }

  it('渲染主导航项', () => {
    renderView();
    const links = document.querySelectorAll('a');
    expect(links.length).toBeGreaterThan(5);
  });

  it('展示当前用户名', () => {
    renderView();
    expect(screen.getByText(/alice/)).toBeInTheDocument();
  });
});