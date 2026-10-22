import { useCallback } from 'react'
import { useSearchParams } from 'react-router'

/**
 * Filters and the page number kept in the URL query string, so a filtered view can be shared
 * and survives a reload. Changing a filter returns to the first page.
 */
export function useUrlFilters<K extends string>(keys: readonly K[]) {
  const [params, setParams] = useSearchParams()

  const filters = Object.fromEntries(keys.map((key) => [key, params.get(key) ?? ''])) as Record<K, string>
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)

  const setFilter = useCallback(
    (key: K, value: string) => {
      setParams((current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        next.delete('page')
        return next
      })
    },
    [setParams],
  )

  const setPage = useCallback(
    (value: number) => {
      setParams((current) => {
        const next = new URLSearchParams(current)
        if (value > 0) next.set('page', String(value))
        else next.delete('page')
        return next
      })
    },
    [setParams],
  )

  const clear = useCallback(() => setParams(new URLSearchParams()), [setParams])
  const active = keys.some((key) => filters[key] !== '')

  return { filters, page, setFilter, setPage, clear, active }
}
