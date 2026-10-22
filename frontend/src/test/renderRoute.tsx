import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { ApiProvider } from '../api/ApiContext'
import type { DashboardApi } from '../api/dashboardApi'
import { routes } from '../routes'
import { createFakeApi } from './fakeApi'

/** Renders the app at `path` with the given API (by default a fake with successful responses). */
export function renderRoute(path: string, api: DashboardApi = createFakeApi()) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const result = render(
    <ApiProvider api={api}>
      <RouterProvider router={router} />
    </ApiProvider>,
  )
  return { ...result, router, api }
}
