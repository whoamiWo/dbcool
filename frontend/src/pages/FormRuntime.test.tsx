/**
 * PHASE 4.2 覆盖率补测：pages/FormRuntime.tsx - 现有功能增强测试
 * 
 * 注意：FormRuntime 目前不支持条件格式 (conditionalStyles)，
 * 该功能属于未来需求，不在本次补测范围内。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor, fireEvent, screen } from '@testing-library/react';
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
vi.mock('@/hooks/useIsMobile', () => ({ useIsMobile: () => false }));

describe('FormRuntime: 基础渲染', () => {
  beforeEach(() => vi.clearAllMocks());

  it('加载表单和集合数据后渲染标题', async () => {
    const form = { id: 'f1', collection_name: 'users', title: '用户表单', layout_json: '[]', rules_json: '{}' };
    const collection = { fields: [] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: form })
      .mockResolvedValueOnce({ code: 0, data: collection });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('用户表单')).toBeInTheDocument();
    }, { timeout: 5000 });
  });
});

describe('FormRuntime: 显隐规则 (visibility)', () => {
  beforeEach(() => vi.clearAllMocks());

  const formWithVisibility = {
    id: 'f1',
    collection_name: 'test',
    title: 'Test Form',
    layout_json: JSON.stringify([{ field: 'age', span: 24 }, { field: 'level', span: 24 }]),
    rules_json: JSON.stringify({
      visibility: { level: { when: 'age', op: 'gt', value: 18 } },
    }),
  };

  it('满足显隐条件时显示目标字段', async () => {
    const collection = { fields: [{ name: 'age', type: 'number', label: '年龄' }, { name: 'level', type: 'select', label: '等级' }] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: formWithVisibility })
      .mockResolvedValueOnce({ code: 0, data: collection });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Form')).toBeInTheDocument();
    }, { timeout: 5000 });

    const ageInput = screen.getByPlaceholderText('年龄');
    fireEvent.change(ageInput, { target: { value: '25' } });

    await waitFor(() => {
      expect(screen.getByText('等级')).toBeInTheDocument();
    });
  });

  it('不满足显隐条件时隐藏目标字段', async () => {
    const collection = { fields: [{ name: 'age', type: 'number', label: '年龄' }, { name: 'level', type: 'select', label: '等级' }] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: formWithVisibility })
      .mockResolvedValueOnce({ code: 0, data: collection });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Form')).toBeInTheDocument();
    }, { timeout: 5000 });

    expect(screen.queryByText('等级')).not.toBeInTheDocument();
  });
});

describe('FormRuntime: 验证规则 (validation)', () => {
  beforeEach(() => vi.clearAllMocks());

  const formWithValidation = {
    id: 'f1',
    collection_name: 'test',
    title: 'Test Form',
    layout_json: JSON.stringify([{ field: 'email', span: 24 }, { field: 'password', span: 24 }]),
    rules_json: JSON.stringify({
      validation: {
        email: [{ type: 'email' }],
        password: [{ type: 'minLength', value: 8 }],
      },
    }),
  };

  it('邮箱格式校验失败显示错误信息', async () => {
    const collection = { fields: [{ name: 'email', type: 'text', label: '邮箱' }, { name: 'password', type: 'text', label: '密码' }] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: formWithValidation })
      .mockResolvedValueOnce({ code: 0, data: collection });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Form')).toBeInTheDocument();
    }, { timeout: 5000 });

    const emailInput = screen.getByPlaceholderText('邮箱');
    fireEvent.change(emailInput, { target: { value: 'invalid-email' } });

    const submitBtn = screen.getByRole('button', { name: /submit/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText('formRuntime.invalidEmail')).toBeInTheDocument();
    });
  });

  it('最小长度校验失败显示错误信息', async () => {
    const collection = { fields: [{ name: 'email', type: 'text', label: '邮箱' }, { name: 'password', type: 'text', label: '密码' }] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: formWithValidation })
      .mockResolvedValueOnce({ code: 0, data: collection });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Form')).toBeInTheDocument();
    }, { timeout: 5000 });

    const passwordInput = screen.getByPlaceholderText('密码');
    fireEvent.change(passwordInput, { target: { value: 'short' } });

    const submitBtn = screen.getByRole('button', { name: /submit/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText('formRuntime.minLength')).toBeInTheDocument();
    });
  });
});

describe('FormRuntime: 提交后动作 (submit rules)', () => {
  beforeEach(() => vi.clearAllMocks());

  const formWithSubmitStay = {
    id: 'f1',
    collection_name: 'test',
    title: 'Test Form',
    layout_json: JSON.stringify([{ field: 'name', span: 24 }]),
    rules_json: JSON.stringify({
      submit: { action: 'stay' },
    }),
  };

  it('action=stay 时显示成功消息', async () => {
    const collection = { fields: [{ name: 'name', type: 'text', label: '姓名' }] };
    
    (apiClient.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ code: 0, data: formWithSubmitStay })
      .mockResolvedValueOnce({ code: 0, data: collection });
    (apiClient.post as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ code: 0, data: { id: 'rec1' } });

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <FormRuntimePage />
        </MemoryRouter>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('Test Form')).toBeInTheDocument();
    }, { timeout: 5000 });

    const nameInput = screen.getByPlaceholderText('姓名');
    fireEvent.change(nameInput, { target: { value: 'John' } });

    const submitBtn = screen.getByRole('button', { name: /submit/i });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText('formRuntime.submitSuccess')).toBeInTheDocument();
    });
  });
});
