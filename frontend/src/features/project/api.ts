import apiClient from '@/api/client';

export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface Task {
  id: string;
  projectId: string;
  parentId?: string | null;
  title: string;
  description?: string;
  status: TaskStatus;
  priority: TaskPriority;
  assigneeId?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  progress: number;
  sortOrder: number;
}

/** 甘特节点:任务 + 递归子任务 */
export interface GanttNode {
  id: string;
  title: string;
  status: string;
  priority: string;
  progress: number;
  startDate?: string | null;
  endDate?: string | null;
  assigneeId?: string | null;
  children: GanttNode[];
}

interface Envelope<T> {
  code: number;
  message: string;
  data: T;
  total?: number;
}

function unwrap<T>(r: unknown): T {
  if (r && typeof r === 'object' && 'data' in (r as Record<string, unknown>)) {
    return (r as Envelope<T>).data;
  }
  return r as T;
}

export const projectApi = {
  listTasks: async (projectId: string, status?: string): Promise<Task[]> => {
    const r = await apiClient.get<Envelope<Task[]>>(`/projects/${projectId}/tasks`, {
      params: status ? { status } : {},
    });
    return unwrap<Task[]>(r);
  },

  gantt: async (projectId: string): Promise<GanttNode[]> => {
    const r = await apiClient.get<Envelope<GanttNode[]>>(`/projects/${projectId}/gantt`);
    return unwrap<GanttNode[]>(r);
  },

  myTasks: async (): Promise<Task[]> => {
    const r = await apiClient.get<Envelope<Task[]>>('/projects/tasks/mine');
    return unwrap<Task[]>(r);
  },

  createTask: async (body: Record<string, unknown>): Promise<Task> => {
    const r = await apiClient.post<Envelope<Task>>('/projects/tasks', body);
    return unwrap<Task>(r);
  },

  updateTask: async (id: string, body: Record<string, unknown>): Promise<Task> => {
    const r = await apiClient.put<Envelope<Task>>(`/projects/tasks/${id}`, body);
    return unwrap<Task>(r);
  },

  deleteTask: async (id: string): Promise<void> => {
    await apiClient.delete(`/projects/tasks/${id}`);
  },
};
