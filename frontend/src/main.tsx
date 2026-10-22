import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { ApiProvider } from './api/ApiContext'
import type { DashboardApi } from './api/dashboardApi'
import { createHttpApi } from './api/httpApi'
import { AuthProvider } from './auth/AuthContext'
import { createHttpAuthApi, type AuthApi } from './auth/authApi'
import { createSessionStore, type SessionStore } from './auth/sessionStore'
import { config, DEMO_MODE } from './config'
import { routes } from './routes'
import './styles.css'

async function createApis(): Promise<{ api: DashboardApi; authApi: AuthApi; store: SessionStore }> {
  if (DEMO_MODE) {
    // Removed from live builds (DEMO_MODE is a build-time constant), so they never contain the dataset.
    const [{ createDemoApi }, { createDemoAuthApi, DEMO_SESSION }, dataset] = await Promise.all([
      import('./demo/demoApi'),
      import('./demo/demoAuth'),
      import('./demo/loadDemoData'),
    ])
    return {
      api: createDemoApi(await dataset.loadDemoData()),
      authApi: createDemoAuthApi(),
      store: createSessionStore(DEMO_SESSION),
    }
  }
  const store = createSessionStore()
  return {
    api: createHttpApi({
      base: config.apiBase,
      timeoutMs: config.requestTimeout,
      session: { csrfToken: () => store.get().session?.csrfToken, onUnauthorized: store.expire },
    }),
    authApi: createHttpAuthApi({ base: config.apiBase, timeoutMs: config.requestTimeout }),
    store,
  }
}

const router = createBrowserRouter(routes)

void createApis().then(({ api, authApi, store }) => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <AuthProvider authApi={authApi} store={store}>
        <ApiProvider api={api}>
          <RouterProvider router={router} />
        </ApiProvider>
      </AuthProvider>
    </StrictMode>,
  )
})
