import { createContext, useContext, type ReactNode } from 'react'
import type { DashboardApi } from './dashboardApi'

const ApiContext = createContext<DashboardApi | null>(null)

export function ApiProvider({ api, children }: { api: DashboardApi; children: ReactNode }) {
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useApi(): DashboardApi {
  const api = useContext(ApiContext)
  if (!api) throw new Error('useApi must be used inside <ApiProvider>')
  return api
}
