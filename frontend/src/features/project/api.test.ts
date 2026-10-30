/**
 * PHASE 4.2 覆盖率补测：features/project/api.ts
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock 整个 client 模块
vi.mock('@/api/client', () => {
  const mockClient = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  };
  return { default: mockClient };
});

import apiClient from '@/api/client';
import * as projectApi from './api';

/** TS: apiClient 方法为泛型函数，mock 后需断言为 vi.fn 才能调 mockResolvedValue。 */
const mockedGet = apiClient.get as unknown as ReturnType<typeof vi.fn>;
const mockedPost = apiClient.post as unknown as ReturnType<typeof vi.fn>;
const mockedPut = apiClient.put as unknown as ReturnType<typeof vi.fn>;
const mockedDelete = apiClient.delete as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
});

describe('projectApi', () => {
  const envelope = <T>(data: T) => ({ code: 0, message: 'ok', data });

  it('listProjects 解包 envelope.data', async () => {
    const projects = [{ id: 'p1', name: 'Project A', ownerId: 'u1', createdAt: '2024-01-01', updatedAt: '2024-01-01' }];
    mockedGet.mockResolvedValue(envelope(projects));
    const result = await projectApi.projectApi.listProjects();
    expect(mockedGet).toHaveBeenCalledWith('/projects');
    expect(result).toEqual(projects);
  });

  it('createProject 返回解包后的 Project', async () => {
    const proj = { id: 'p2', name: 'New', ownerId: 'u1', createdAt: '2024-01-02', updatedAt: '2024-01-02' };
    mockedPost.mockResolvedValue(envelope(proj));
    const result = await projectApi.projectApi.createProject({ name: 'New' });
    expect(mockedPost).toHaveBeenCalledWith('/projects', { name: 'New' });
    expect(result.id).toBe('p2');
  });

  it('listTasks 支持可选 status 参数', async () => {
    const tasks = [{ id: 't1', projectId: 'p1', title: 'Task 1', status: 'TODO', priority: 'MEDIUM', progress: 0, sortOrder: 1 } as projectApi.Task];
    mockedGet.mockResolvedValue(envelope(tasks));
    const result = await projectApi.projectApi.listTasks('p1', 'TODO');
    expect(mockedGet).toHaveBeenCalledWith('/projects/p1/tasks', { params: { status: 'TODO' } });
    expect(result[0].id).toBe('t1');
  });

  it('gantt 返回树形结构', async () => {
    const nodes = [{ id: 'n1', title: 'Node', status: 'TODO', priority: 'MEDIUM', progress: 0, children: [] } as projectApi.GanttNode];
    mockedGet.mockResolvedValue(envelope(nodes));
    const result = await projectApi.projectApi.gantt('p1');
    expect(mockedGet).toHaveBeenCalledWith('/projects/p1/gantt');
    expect(Array.isArray(result)).toBe(true);
  });

  it('myTasks 调用 /projects/tasks/mine', async () => {
    mockedGet.mockResolvedValue(envelope([]));
    await projectApi.projectApi.myTasks();
    expect(mockedGet).toHaveBeenCalledWith('/projects/tasks/mine');
  });

  it('createTask /projects/tasks POST', async () => {
    const task = { id: 't2', projectId: 'p1', title: 'New', status: 'TODO', priority: 'LOW', progress: 0, sortOrder: 1 } as projectApi.Task;
    mockedPost.mockResolvedValue(envelope(task));
    await projectApi.projectApi.createTask({ title: 'New' });
    expect(mockedPost).toHaveBeenCalledWith('/projects/tasks', { title: 'New' });
  });

  it('updateTask PUT /projects/tasks/:id', async () => {
    mockedPut.mockResolvedValue(undefined);
    await projectApi.projectApi.updateTask('t1', { status: 'DONE' });
    expect(mockedPut).toHaveBeenCalledWith('/projects/tasks/t1', { status: 'DONE' });
  });

  it('deleteTask DELETE /projects/tasks/:id', async () => {
    mockedDelete.mockResolvedValue(undefined);
    await projectApi.projectApi.deleteTask('t1');
    expect(mockedDelete).toHaveBeenCalledWith('/projects/tasks/t1');
  });

  it('listChecklists 解包 checklist', async () => {
    const checklists = [{ id: 'c1', title: 'Check', sortOrder: 1 } as projectApi.CardChecklist];
    mockedGet.mockResolvedValue(envelope(checklists));
    const result = await projectApi.projectApi.listChecklists('t1');
    expect(mockedGet).toHaveBeenCalledWith('/project-boards/checklists/by-task/t1');
    expect(result[0].title).toBe('Check');
  });

  it('listChecklistItems 解包 checklist items', async () => {
    const items = [{ id: 'i1', title: 'Item', done: false, sortOrder: 1 } as projectApi.CardChecklistItem];
    mockedGet.mockResolvedValue(envelope(items));
    const result = await projectApi.projectApi.listChecklistItems('c1');
    expect(mockedGet).toHaveBeenCalledWith('/project-boards/checklist-items/by-checklist/c1');
    expect(result[0].done).toBe(false);
  });

  it('updateChecklistItem PUT 无返回值', async () => {
    mockedPut.mockResolvedValue(undefined);
    await projectApi.projectApi.updateChecklistItem('i1', { done: true });
    expect(mockedPut).toHaveBeenCalledWith('/project-boards/checklist-items/i1', { done: true });
  });
});
