import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Suspense, lazy } from 'react';
import { AppLayout } from './components/AppLayout';
import { LoginPage } from './pages/Login';
import { HomePage } from './pages/Home';

// R12 代码分割 — React.lazy + 命名导出桥接(不改 29 个页面文件)
const SchemaDesignerPage = lazy(() =>
  import('./pages/SchemaDesigner').then(m => ({ default: m.SchemaDesignerPage } as { default: React.ComponentType })),
);
const SchemaEditorPage = lazy(() =>
  import('./pages/SchemaEditor').then(m => ({ default: m.SchemaEditorPage } as { default: React.ComponentType })),
);
const CollectionsListPage = lazy(() =>
  import('./pages/CollectionsList').then(m => ({ default: m.CollectionsListPage } as { default: React.ComponentType })),
);
const CollectionDetailPage = lazy(() =>
  import('./pages/CollectionDetail').then(m => ({ default: m.CollectionDetailPage } as { default: React.ComponentType })),
);
const FormDesignerPage = lazy(() =>
  import('./pages/FormDesigner').then(m => ({ default: m.FormDesignerPage } as { default: React.ComponentType })),
);
const FormRuntimePage = lazy(() =>
  import('./pages/FormRuntime').then(m => ({ default: m.FormRuntimePage } as { default: React.ComponentType })),
);
const FormsListPage = lazy(() =>
  import('./pages/FormsList').then(m => ({ default: m.FormsListPage } as { default: React.ComponentType })),
);
const TableViewPage = lazy(() =>
  import('./pages/TableView').then(m => ({ default: m.TableViewPage } as { default: React.ComponentType })),
);
const GalleryViewPage = lazy(() =>
  import('./pages/GalleryView').then(m => ({ default: m.GalleryViewPage } as { default: React.ComponentType })),
);
const CalendarViewPage = lazy(() =>
  import('./pages/CalendarView').then(m => ({ default: m.CalendarViewPage } as { default: React.ComponentType })),
);
const KanbanViewPage = lazy(() =>
  import('./pages/KanbanView').then(m => ({ default: m.KanbanViewPage } as { default: React.ComponentType })),
);
const DetailViewPage = lazy(() =>
  import('./pages/DetailView').then(m => ({ default: m.DetailViewPage } as { default: React.ComponentType })),
);
const ViewDesignerPage = lazy(() =>
  import('./pages/ViewDesigner').then(m => ({ default: m.ViewDesignerPage } as { default: React.ComponentType })),
);
const ViewsListPage = lazy(() =>
  import('./pages/ViewsList').then(m => ({ default: m.ViewsListPage } as { default: React.ComponentType })),
);
const WorkflowsListPage = lazy(() =>
  import('./pages/WorkflowsList').then(m => ({ default: m.WorkflowsListPage } as { default: React.ComponentType })),
);
const UsersListPage = lazy(() =>
  import('./pages/UsersList').then(m => ({ default: m.UsersListPage } as { default: React.ComponentType })),
);
const WorkflowDesignerPageWithProvider = lazy(() =>
  import('./pages/WorkflowDesigner').then(m => ({ default: m.WorkflowDesignerPageWithProvider } as { default: React.ComponentType })),
);
const WorkflowInstancesPage = lazy(() =>
  import('./pages/WorkflowInstances').then(m => ({ default: m.WorkflowInstancesPage } as { default: React.ComponentType })),
);
const MyTasksPage = lazy(() =>
  import('./pages/MyTasks').then(m => ({ default: m.MyTasksPage } as { default: React.ComponentType })),
);
const ProfilePage = lazy(() =>
  import('./pages/Profile').then(m => ({ default: m.ProfilePage } as { default: React.ComponentType })),
);
const MessagesInboxPage = lazy(() =>
  import('./pages/MessagesInbox').then(m => ({ default: m.MessagesInboxPage } as { default: React.ComponentType })),
);
const RolesListPage = lazy(() =>
  import('./pages/RolesList').then(m => ({ default: m.RolesListPage } as { default: React.ComponentType })),
);
const AclEditorPage = lazy(() =>
  import('./pages/AclEditor').then(m => ({ default: m.AclEditorPage } as { default: React.ComponentType })),
);
const AuditLogsPage = lazy(() =>
  import('./pages/AuditLogs').then(m => ({ default: m.AuditLogsPage } as { default: React.ComponentType })),
);
const RowAclAdminPage = lazy(() =>
  import('./pages/RowAclAdmin').then(m => ({ default: m.RowAclAdminPage } as { default: React.ComponentType })),
);
const NotificationChannelsPage = lazy(() =>
  import('./pages/NotificationChannels').then(m => ({ default: m.NotificationChannelsPage } as { default: React.ComponentType })),
);
const ErDiagramPage = lazy(() =>
  import('./pages/ErDiagram').then(m => ({ default: m.ErDiagramPage } as { default: React.ComponentType })),
);
const AlertCenterPage = lazy(() =>
  import('./pages/AlertCenter').then(m => ({ default: m.AlertCenterPage } as { default: React.ComponentType })),
);
const ImChatPage = lazy(() =>
  import('./features/im/ImLayout').then(m => ({ default: m.ImChatPage } as { default: React.ComponentType })),
);
const KnowledgeBaseListPage = lazy(() =>
  import('./pages/wiki/KnowledgeBaseList').then(m => ({ default: m.KnowledgeBaseListPage } as { default: React.ComponentType })),
);
const WikiPageListPage = lazy(() =>
  import('./pages/wiki/WikiPageList').then(m => ({ default: m.WikiPageListPage } as { default: React.ComponentType })),
);
const WikiPageReadPage = lazy(() =>
  import('./pages/wiki/WikiPageRead').then(m => ({ default: m.WikiPageReadPage } as { default: React.ComponentType })),
);
const WikiPageEditPage = lazy(() =>
  import('./pages/wiki/WikiPageEdit').then(m => ({ default: m.WikiPageEditPage } as { default: React.ComponentType })),
);
const WikiVersionHistoryPage = lazy(() =>
  import('./pages/wiki/WikiVersionHistory').then(m => ({ default: m.WikiVersionHistoryPage } as { default: React.ComponentType })),
);
const WikiSearchPage = lazy(() =>
  import('./pages/wiki/WikiSearch').then(m => ({ default: m.WikiSearchPage } as { default: React.ComponentType })),
);
const WikiCategoryManagementPage = lazy(() =>
  import('./pages/wiki/WikiCategoryManagement').then(m => ({ default: m.WikiCategoryManagementPage } as { default: React.ComponentType })),
);
const BiReportPage = lazy(() =>
  import('./features/bi/BiReportPage').then(m => ({ default: m.default } as { default: React.ComponentType })),
);
const ProjectPage = lazy(() =>
  import('./features/project/ProjectPage').then(m => ({ default: m.default } as { default: React.ComponentType })),
);
const CollabPage = lazy(() =>
  import('./features/realtime/CollabPage').then(m => ({ default: m.default } as { default: React.ComponentType })),
);
const WorkbenchPage = lazy(() =>
  import('./pages/Workbench').then(m => ({ default: m.default } as { default: React.ComponentType })),
);
const DingTalkPage = lazy(() =>
  import('./pages/dingtalk/DingTalkPage').then(m => ({ default: m.DingTalkPage } as { default: React.ComponentType })),
);
const AgentChatPage = lazy(() =>
  import('./pages/agent/AgentChatPage').then(m => ({ default: m.AgentChatPage } as { default: React.ComponentType })),
);
const PlaybookListPage = lazy(() =>
  import('./pages/playbook/PlaybookList').then(m => ({ default: m.PlaybookListPage } as { default: React.ComponentType })),
);
const PlaybookRunPage = lazy(() =>
  import('./pages/playbook/PlaybookRun').then(m => ({ default: m.PlaybookRunPage } as { default: React.ComponentType })),
);

/** R12 懒加载占位 */
function Lazy({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={null}>{children}</Suspense>;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/workbench" replace /> },
      { path: 'home', element: <HomePage /> },

      // Schema
      { path: 'designer/schemas', element: <Lazy><CollectionsListPage /></Lazy> },
      { path: 'designer/schemas/new', element: <Lazy><SchemaDesignerPage /></Lazy> },
      { path: 'designer/schemas/:name/edit', element: <Lazy><SchemaEditorPage /></Lazy> },
      { path: 'designer/collections/:name', element: <Lazy><CollectionDetailPage /></Lazy> },

      // Form
      { path: 'designer/forms', element: <Lazy><FormsListPage /></Lazy> },
      { path: 'designer/forms/:collection/new', element: <Lazy><FormDesignerPage /></Lazy> },
      { path: 'designer/forms/:collection/:id/edit', element: <Lazy><FormDesignerPage /></Lazy> },
      { path: 'forms/:formId/fill', element: <Lazy><FormRuntimePage /></Lazy> },

      // View
      { path: 'designer/views', element: <Lazy><ViewsListPage /></Lazy> },
      { path: 'designer/views/:collection/new', element: <Lazy><ViewDesignerPage /></Lazy> },
      { path: 'admin/users', element: <Lazy><UsersListPage /></Lazy> },
      { path: 'designer/workflows', element: <Lazy><WorkflowsListPage /></Lazy> },
      { path: 'designer/instances', element: <Lazy><WorkflowInstancesPage /></Lazy> },
      { path: 'designer/instances/:id', element: <Lazy><WorkflowInstancesPage /></Lazy> },
      { path: 'tasks/my', element: <Lazy><MyTasksPage /></Lazy> },
      { path: 'designer/workflows/new', element: <Lazy><WorkflowDesignerPageWithProvider /></Lazy> },
      { path: 'designer/workflows/:id/edit', element: <Lazy><WorkflowDesignerPageWithProvider /></Lazy> },
      { path: 'profile', element: <Lazy><ProfilePage /></Lazy> },
      { path: 'messages', element: <Lazy><MessagesInboxPage /></Lazy> },
      { path: 'admin/roles', element: <Lazy><RolesListPage /></Lazy> },
      { path: 'admin/acl', element: <Lazy><AclEditorPage /></Lazy> },
      { path: 'admin/audit', element: <Lazy><AuditLogsPage /></Lazy> },
      { path: 'admin/row-acl', element: <Lazy><RowAclAdminPage /></Lazy> },
      { path: 'admin/notifications', element: <Lazy><NotificationChannelsPage /></Lazy> },
      { path: 'admin/alerts', element: <Lazy><AlertCenterPage /></Lazy> },
      { path: 'admin/er', element: <Lazy><ErDiagramPage /></Lazy> },
      { path: 'designer/views/:collection/:id/edit', element: <Lazy><ViewDesignerPage /></Lazy> },
      { path: 'views/:id/run', element: <Lazy><TableViewPage /></Lazy> },
      { path: 'views/:id/kanban', element: <Lazy><KanbanViewPage /></Lazy> },
      { path: 'views/:id/gallery', element: <Lazy><GalleryViewPage /></Lazy> },
      { path: 'views/:id/calendar', element: <Lazy><CalendarViewPage /></Lazy> },
      { path: 'views/:id/detail/:recordId', element: <Lazy><DetailViewPage /></Lazy> },
      { path: 'workbench', element: <Lazy><WorkbenchPage /></Lazy> },
      { path: 'im', element: <Lazy><ImChatPage /></Lazy> },
      { path: 'im/:channelId', element: <Lazy><ImChatPage /></Lazy> },

      // Wiki
      { path: 'wiki/kb', element: <Lazy><KnowledgeBaseListPage /></Lazy> },
      { path: 'wiki/kb/:id', element: <Lazy><WikiPageListPage /></Lazy> },
      { path: 'wiki/:slug', element: <Lazy><WikiPageReadPage /></Lazy> },
      { path: 'wiki/:slug/edit', element: <Lazy><WikiPageEditPage /></Lazy> },
      { path: 'wiki/:slug/versions', element: <Lazy><WikiVersionHistoryPage /></Lazy> },
      { path: 'wiki/search', element: <Lazy><WikiSearchPage /></Lazy> },
      { path: 'wiki/kb/:kbId/categories', element: <Lazy><WikiCategoryManagementPage /></Lazy> },
      { path: 'bi', element: <Lazy><BiReportPage /></Lazy> },
      { path: 'projects', element: <Lazy><ProjectPage /></Lazy> },
      { path: 'collab/:docId', element: <Lazy><CollabPage /></Lazy> },
      { path: 'dingtalk', element: <Lazy><DingTalkPage /></Lazy> },

      // Playbooks
      { path: 'playbooks', element: <Lazy><PlaybookListPage /></Lazy> },
      { path: 'playbooks/:id', element: <Lazy><PlaybookRunPage /></Lazy> },
      { path: 'agent', element: <Lazy><AgentChatPage /></Lazy> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
