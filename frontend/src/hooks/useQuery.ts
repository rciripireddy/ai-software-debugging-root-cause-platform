import { useCallback, useEffect, useEffectEvent, useState } from 'react'

export interface QueryState<T> {
  data: T | undefined
  error: unknown
  /** True until the first response (or error) for the current key. */
  loading: boolean
  /** True while a reload or automatic refresh is running and data is already shown. */
  refreshing: boolean
  updatedAt: Date | undefined
  reload: () => void
}

interface QueryOptions {
  /** Automatic refresh interval in milliseconds; omitted or 0 means manual refresh only. */
  refreshMs?: number
  /** When false, nothing is loaded (e.g. no incident selected yet). */
  enabled?: boolean
}

/**
 * Loads data with `load` whenever `key` changes, optionally refreshing it periodically.
 * Requests that become stale (new key, unmount) are aborted. Data stays visible while a
 * refresh runs; a failed refresh keeps the previous data and reports the error.
 */
export function useQuery<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  { refreshMs = 0, enabled = true }: QueryOptions = {},
): QueryState<T> {
  const [state, setState] = useState<{ key: string; data?: T; error?: unknown; updatedAt?: Date }>({ key })
  const [running, setRunning] = useState(false)
  const [reloads, setReloads] = useState(0)
  // Always calls the latest `load` without restarting the effect when the function identity changes.
  const loadLatest = useEffectEvent((signal: AbortSignal) => load(signal))

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    let timer: number | undefined

    const run = async () => {
      setRunning(true)
      try {
        const data = await loadLatest(controller.signal)
        if (!controller.signal.aborted) setState({ key, data, updatedAt: new Date() })
      } catch (error) {
        if (!controller.signal.aborted) {
          setState((previous) => (previous.key === key ? { ...previous, error } : { key, error }))
        }
      } finally {
        if (!controller.signal.aborted) {
          setRunning(false)
          if (refreshMs > 0) timer = window.setTimeout(() => void run(), refreshMs)
        }
      }
    }

    void run()
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [key, refreshMs, enabled, reloads])

  const reload = useCallback(() => setReloads((count) => count + 1), [])
  const current = state.key === key ? state : { key }

  return {
    data: current.data,
    error: current.error,
    loading: enabled && current.data === undefined && current.error === undefined,
    refreshing: running && current.data !== undefined,
    updatedAt: current.updatedAt,
    reload,
  }
}
