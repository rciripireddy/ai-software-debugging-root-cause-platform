import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { ApiProvider } from './api/ApiContext'
import type { DashboardApi } from './api/dashboardApi'
import { createHttpApi } from './api/httpApi'
import { config, DEMO_MODE } from './config'
import { routes } from './routes'
import './styles.css'

async function createApi(): Promise<DashboardApi> {
  if (DEMO_MODE) {
    // Removed from live builds (DEMO_MODE is a build-time constant), so they never contain the dataset.
    const [{ createDemoApi }, dataset] = await Promise.all([import('./demo/demoApi'), import('./demo/loadDemoData')])
    return createDemoApi(await dataset.loadDemoData())
  }
  return createHttpApi({ base: config.apiBase, timeoutMs: config.requestTimeout })
}

const router = createBrowserRouter(routes)

void createApi().then((api) => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <ApiProvider api={api}>
        <RouterProvider router={router} />
      </ApiProvider>
    </StrictMode>,
  )
})
