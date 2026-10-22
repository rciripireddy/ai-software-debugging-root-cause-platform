import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { ApiProvider } from '../api/ApiContext'
import type { DashboardApi } from '../api/dashboardApi'
import { AuthProvider } from '../auth/AuthContext'
import type { AuthApi } from '../auth/authApi'
import { createSessionStore } from '../auth/sessionStore'
import type { Session } from '../auth/types'
import { routes } from '../routes'
import { createFakeApi } from './fakeApi'
import { createFakeAuthApi, session as testSession } from './fakeAuth'

interface RenderOptions {
  /** The signed-in session; null renders the app signed out. Default: an ADMIN session. */
  session?: Session | null
  authApi?: AuthApi
}

/** Renders the app at `path` with the given API (by default a fake with successful responses). */
export function renderRoute(path: string, api: DashboardApi = createFakeApi(), options: RenderOptions = {}) {
  const store = createSessionStore(options.session === undefined ? testSession('ADMIN') : options.session)
  const authApi = options.authApi ?? createFakeAuthApi({ mode: api.mode })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const result = render(
    <AuthProvider authApi={authApi} store={store}>
      <ApiProvider api={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </AuthProvider>,
  )
  return { ...result, router, api, store, authApi }
}
