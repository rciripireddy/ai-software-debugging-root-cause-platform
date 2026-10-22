import type { RouteObject } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { AppLayout } from './components/AppLayout'
import { ErrorMonitoringPage } from './pages/ErrorMonitoringPage'
import { IncidentDetailPage } from './pages/IncidentDetailPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { RootCauseAnalysisPage } from './pages/RootCauseAnalysisPage'
import { RouteErrorPage } from './pages/RouteErrorPage'
import { ServiceHealthPage } from './pages/ServiceHealthPage'

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    // Every dashboard page needs a signed-in user (the demo build has a built-in demo session).
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    // A rendering error in one page is shown in place of that page; the layout stays usable.
    children: [
      {
        errorElement: <RouteErrorPage />,
        children: [
          { index: true, element: <OverviewPage /> },
          { path: 'errors', element: <ErrorMonitoringPage /> },
          { path: 'incidents', element: <IncidentsPage /> },
          { path: 'incidents/:incidentId', element: <IncidentDetailPage /> },
          { path: 'root-cause', element: <RootCauseAnalysisPage /> },
          { path: 'root-cause/:incidentId', element: <RootCauseAnalysisPage /> },
          { path: 'health', element: <ServiceHealthPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
