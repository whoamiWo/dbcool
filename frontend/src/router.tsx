import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppLayout } from './components/AppLayout';
import { LoginPage } from './pages/Login';
import { HomePage } from './pages/Home';
import { SchemaDesignerPage } from './pages/SchemaDesigner';
import { SchemaEditorPage } from './pages/SchemaEditor';
import { CollectionsListPage } from './pages/CollectionsList';
import { CollectionDetailPage } from './pages/CollectionDetail';
import { FormDesignerPage } from './pages/FormDesigner';
import { FormRuntimePage } from './pages/FormRuntime';
import { FormsListPage } from './pages/FormsList';
import { TableViewPage } from './pages/TableView';
import { KanbanViewPage } from './pages/KanbanView';
import { DetailViewPage } from './pages/DetailView';
import { ViewDesignerPage } from './pages/ViewDesigner';
import { ViewsListPage } from './pages/ViewsList';
import { WorkflowsListPage } from './pages/WorkflowsList';
import { UsersListPage } from './pages/UsersList';
import { WorkflowDesignerPageWithProvider } from './pages/WorkflowDesigner';
import { WorkflowInstancesPage } from './pages/WorkflowInstances';
import { MyTasksPage } from './pages/MyTasks';
import { ProfilePage } from './pages/Profile';
import { MessagesInboxPage } from './pages/MessagesInbox';
import { RolesListPage } from './pages/RolesList';
import { AclEditorPage } from './pages/AclEditor';
import { AuditLogsPage } from './pages/AuditLogs';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/home" replace /> },
      { path: 'home', element: <HomePage /> },

      // Schema
      { path: 'designer/schemas', element: <CollectionsListPage /> },
      { path: 'designer/schemas/new', element: <SchemaDesignerPage /> },
      { path: 'designer/schemas/:name/edit', element: <SchemaEditorPage /> },
      { path: 'designer/collections/:name', element: <CollectionDetailPage /> },

      // Form
      { path: 'designer/forms', element: <FormsListPage /> },
      { path: 'designer/forms/:collection/new', element: <FormDesignerPage /> },
      { path: 'designer/forms/:collection/:id/edit', element: <FormDesignerPage /> },
      { path: 'forms/:formId/fill', element: <FormRuntimePage /> },

      // View
      { path: 'designer/views', element: <ViewsListPage /> },
      { path: 'designer/views/:collection/new', element: <ViewDesignerPage /> },
      { path: 'admin/users', element: <UsersListPage /> },
      { path: 'designer/workflows', element: <WorkflowsListPage /> },
      { path: 'designer/instances', element: <WorkflowInstancesPage /> },
      { path: 'designer/instances/:id', element: <WorkflowInstancesPage /> },
      { path: 'tasks/my', element: <MyTasksPage /> },
      { path: 'designer/workflows/new', element: <WorkflowDesignerPageWithProvider /> },
      { path: 'designer/workflows/:id/edit', element: <WorkflowDesignerPageWithProvider /> },
      { path: 'profile', element: <ProfilePage /> },
      { path: 'messages', element: <MessagesInboxPage /> },
      { path: 'admin/roles', element: <RolesListPage /> },
      { path: 'admin/acl', element: <AclEditorPage /> },
      { path: 'admin/audit', element: <AuditLogsPage /> },
      { path: 'designer/views/:collection/:id/edit', element: <ViewDesignerPage /> },
      { path: 'views/:id/run', element: <TableViewPage /> },
      { path: 'views/:id/kanban', element: <KanbanViewPage /> },
      { path: 'views/:id/detail/:recordId', element: <DetailViewPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
