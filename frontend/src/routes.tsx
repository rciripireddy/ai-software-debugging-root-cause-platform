import type { RouteObject } from 'react-router'
import { AppLayout } from './components/AppLayout'
import { ErrorMonitoringPage } from './pages/ErrorMonitoringPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { RootCauseAnalysisPage } from './pages/RootCauseAnalysisPage'
import { ServiceHealthPage } from './pages/ServiceHealthPage'

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'errors', element: <ErrorMonitoringPage /> },
      { path: 'root-cause', element: <RootCauseAnalysisPage /> },
      { path: 'health', element: <ServiceHealthPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
