/**
 * PHASE 4.2 覆盖率补测：features/project/ProjectPage.tsx
 * 覆盖：loading、项目列表/空态、选中项目后 Tab 看板、新建项目对话框。
 * 注：TaskBoard / GanttView / BoardView 为重量子组件，此处 mock 掉只验证装配。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ProjectPage from './ProjectPage';
import { projectApi } from './api';

vi.mock('./api', () => ({
  projectApi: { listProjects: vi.fn(), createProject: vi.fn() },
}));
vi.mock('./TaskBoard', () => ({ __esModule: true, default: () => <div data-testid="task-board" /> }));
vi.mock('./GanttView', () => ({
  __esModule: true,
  default: () => <div data-testid="gantt-view" />,
  GanttLegend: () => <div data-testid="gantt-legend" />,
}));
vi.mock('./BoardView', () => ({ BoardView: () => <div data-testid="board-view" /> }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }));

const mockedList = projectApi.listProjects as unknown as ReturnType<typeof vi.fn>;
const mockedCreate = projectApi.createProject as unknown as ReturnType<typeof vi.fn>;

const projects = [
  { id: 'p1', name: '项目一', description: '描述一', createdAt: '2024-01-01T00:00:00Z' },
  { id: 'p2', name: '项目二', description: '描述二', createdAt: '2024-02-01T00:00:00Z' },
];

function renderPage(url = '/projects') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[url]}>
        <ProjectPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedList.mockResolvedValue(projects);
  mockedCreate.mockResolvedValue({ id: 'p3', name: '新建项目' });
});

describe('ProjectPage', () => {
  it('加载中渲染进度圈', () => {
    mockedList.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(document.querySelector('.MuiCircularProgress-root')).toBeTruthy();
  });

  it('渲染项目列表', async () => {
    renderPage();
    await waitFor(() => expect(mockedList).toHaveBeenCalled());
    expect(await screen.findByText('项目一')).toBeInTheDocument();
    expect(screen.getByText('项目二')).toBeInTheDocument();
  });

  it('空项目列表渲染空态', async () => {
    mockedList.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText('project.emptyProjects')).toBeInTheDocument());
  });

  it('点击项目写入 URL 选中该项目', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('项目一')).toBeInTheDocument());
    fireEvent.click(screen.getByText('项目一'));
    // 选中后展示返回按钮与看板 Tab
    await waitFor(() => expect(screen.getByText('project.backToList')).toBeInTheDocument());
    expect(screen.getByTestId('board-view')).toBeInTheDocument();
  });

  it('URL 带 projectId 时直接渲染看板', async () => {
    renderPage('/projects?projectId=p1');
    await waitFor(() => expect(screen.getByTestId('board-view')).toBeInTheDocument());
    expect(screen.getByText('project.backToList')).toBeInTheDocument();
  });

  it('切换 Tab 渲染对应看板（tab0=BoardView / 1=TaskBoard / 2=Gantt）', async () => {
    renderPage('/projects?projectId=p1');
    // tab0 → BoardView
    await waitFor(() => expect(screen.getByTestId('board-view')).toBeInTheDocument());

    // tab1 → TaskBoard
    fireEvent.click(screen.getByText('简易看板'));
    await waitFor(() => expect(screen.getByTestId('task-board')).toBeInTheDocument());

    // tab2 → GanttLegend + GanttView
    fireEvent.click(screen.getByText('甘特图'));
    await waitFor(() => expect(screen.getByTestId('gantt-view')).toBeInTheDocument());
    expect(screen.getByTestId('gantt-legend')).toBeInTheDocument();
  });

  it('点击返回列表清空选中', async () => {
    renderPage('/projects?projectId=p1');
    await waitFor(() => expect(screen.getByText('project.backToList')).toBeInTheDocument());
    fireEvent.click(screen.getByText('project.backToList'));
    await waitFor(() => expect(screen.getByText('project.myProjects')).toBeInTheDocument());
  });

  it('新建项目：填写名称后创建', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('project.newProject')).toBeInTheDocument());
    fireEvent.click(screen.getByText('project.newProject'));

    const nameInput = await screen.findByPlaceholderText('project.projectNamePlaceholder');
    fireEvent.change(nameInput, { target: { value: '新建项目' } });
    fireEvent.change(screen.getByLabelText('project.projectDesc'), { target: { value: '描述' } });

    fireEvent.click(screen.getByText('project.create'));

    await waitFor(() =>
      expect(mockedCreate).toHaveBeenCalledWith(
        expect.objectContaining({ name: '新建项目', description: '描述' }),
      ),
    );
  });

  it('新建项目：名称为空时不创建', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('project.newProject')).toBeInTheDocument());
    fireEvent.click(screen.getByText('project.newProject'));
    await screen.findByPlaceholderText('project.projectNamePlaceholder');

    // 名称为空时创建按钮 disabled
    const createBtn = screen.getByText('project.create');
    expect(createBtn).toBeDisabled();
    expect(mockedCreate).not.toHaveBeenCalled();
  });
});
