/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_INGESTION_API_BASE?: string
  readonly VITE_INCIDENT_API_BASE?: string
  readonly VITE_ANALYSIS_API_BASE?: string
  readonly VITE_REFRESH_OVERVIEW_SECONDS?: string
  readonly VITE_REFRESH_LISTS_SECONDS?: string
  readonly VITE_REFRESH_HEALTH_SECONDS?: string
  readonly VITE_REQUEST_TIMEOUT_SECONDS?: string
}
