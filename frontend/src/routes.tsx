import type { RouteObject } from 'react-router'
import { AppLayout } from './components/AppLayout'
import { ErrorMonitoringPage } from './pages/ErrorMonitoringPage'
import { IncidentDetailPage } from './pages/IncidentDetailPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { RootCauseAnalysisPage } from './pages/RootCauseAnalysisPage'
import { RouteErrorPage } from './pages/RouteErrorPage'
import { ServiceHealthPage } from './pages/ServiceHealthPage'

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
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
