import { useCallback, useEffect, useState } from 'react'
import { checkHealth, type HealthResult, type MonitoredService } from '../services/serviceHealth'

export const HEALTH_REFRESH_INTERVAL_MS = 15_000

type HealthResults = Record<string, HealthResult>

async function checkAll(services: MonitoredService[]): Promise<HealthResults> {
  const entries = await Promise.all(
    services.map(async (service) => [service.id, await checkHealth(service.healthPath)] as const),
  )
  return Object.fromEntries(entries)
}

export function useServiceHealth(services: MonitoredService[]) {
  const [results, setResults] = useState<HealthResults>({})
  const [isRefreshing, setIsRefreshing] = useState(false)

  useEffect(() => {
    let cancelled = false
    const poll = async () => {
      const next = await checkAll(services)
      if (!cancelled) setResults(next)
    }

    void poll()
    const timer = window.setInterval(() => void poll(), HEALTH_REFRESH_INTERVAL_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [services])

  const refresh = useCallback(async () => {
    setIsRefreshing(true)
    try {
      setResults(await checkAll(services))
    } finally {
      setIsRefreshing(false)
    }
  }, [services])

  return { results, isRefreshing, refresh }
}
