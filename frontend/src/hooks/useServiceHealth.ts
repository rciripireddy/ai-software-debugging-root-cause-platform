import { useCallback, useEffect, useState } from 'react'
import type { DashboardApi, MonitoredService } from '../api/dashboardApi'
import type { HealthResult } from '../types/api'

type HealthResults = Partial<Record<MonitoredService['id'], HealthResult>>

async function checkAll(api: DashboardApi): Promise<HealthResults> {
  const entries = await Promise.all(
    api.services.map(async (service) => [service.id, await api.checkHealth(service)] as const),
  )
  return Object.fromEntries(entries)
}

/** Checks every service now and then every `intervalMs`. A failing check never throws. */
export function useServiceHealth(api: DashboardApi, intervalMs: number) {
  const [results, setResults] = useState<HealthResults>({})
  const [isRefreshing, setIsRefreshing] = useState(false)

  useEffect(() => {
    let cancelled = false
    const poll = async () => {
      const next = await checkAll(api)
      if (!cancelled) setResults(next)
    }

    void poll()
    const timer = window.setInterval(() => void poll(), intervalMs)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [api, intervalMs])

  const refresh = useCallback(async () => {
    setIsRefreshing(true)
    try {
      setResults(await checkAll(api))
    } finally {
      setIsRefreshing(false)
    }
  }, [api])

  return { results, isRefreshing, refresh }
}
