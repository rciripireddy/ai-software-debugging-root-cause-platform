import { ApiError } from '../api/http'

/** A short title and explanation for an error shown in the UI. */
export function describeError(error: unknown): { title: string; detail: string } {
  if (error instanceof ApiError) {
    if (error.isUnavailable) {
      return {
        title: `${serviceLabel(error.service)} unavailable`,
        detail: `${error.message} Other parts of the dashboard keep working.`,
      }
    }
    if (error.kind === 'invalid-response') return { title: 'Unexpected response', detail: error.message }
    return {
      title: error.title,
      detail: error.correlationId ? `${error.message} (correlation ID ${error.correlationId})` : error.message,
    }
  }
  return { title: 'Something went wrong', detail: error instanceof Error ? error.message : String(error) }
}

export function serviceLabel(service: string): string {
  switch (service) {
    case 'ingestion-service':
      return 'Ingestion service'
    case 'incident-service':
      return 'Incident service'
    case 'analysis-service':
      return 'Analysis service'
    default:
      return service
  }
}
