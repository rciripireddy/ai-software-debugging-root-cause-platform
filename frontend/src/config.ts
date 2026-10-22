// Dashboard configuration, read from Vite environment variables at build time
// (see docs/configuration.md). Every value has a default that works with the Vite dev server
// proxy and the nginx proxy in Docker.

function env(name: string): string | undefined {
  const value = (import.meta.env as Record<string, string | undefined>)[name]
  return value === undefined || value === '' ? undefined : value
}

function seconds(name: string, fallback: number): number {
  const value = Number(env(name))
  return Number.isFinite(value) && value > 0 ? value * 1000 : fallback * 1000
}

/**
 * Demo mode serves bundled synthetic data and makes no API requests. Enabled by building or
 * serving with `--mode demo` (`npm run build:demo`, `npm run dev:demo`); live builds do not
 * contain the demo data at all (see vite.config.ts).
 */
export const DEMO_MODE = import.meta.env.MODE === 'demo'

export const config = {
  demoMode: DEMO_MODE,

  /** API base paths. Relative paths go through the dev server or nginx proxy. */
  apiBase: {
    ingestion: env('VITE_INGESTION_API_BASE') ?? '/api/ingestion',
    incident: env('VITE_INCIDENT_API_BASE') ?? '/api/incident',
    analysis: env('VITE_ANALYSIS_API_BASE') ?? '/api/analysis',
  },

  /** Automatic refresh intervals in milliseconds. */
  refresh: {
    overview: seconds('VITE_REFRESH_OVERVIEW_SECONDS', 30),
    lists: seconds('VITE_REFRESH_LISTS_SECONDS', 60),
    health: seconds('VITE_REFRESH_HEALTH_SECONDS', 15),
  },

  /** Request timeout in milliseconds. */
  requestTimeout: seconds('VITE_REQUEST_TIMEOUT_SECONDS', 10),
} as const

export type AppConfig = typeof config
