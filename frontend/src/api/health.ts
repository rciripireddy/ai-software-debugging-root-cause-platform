import type { HealthResult } from '../types/api'
import type { MonitoredService } from './dashboardApi'

// Readiness endpoints: "healthy" means the service can do its work (its database is reachable),
// not only that the process is running.
export function monitoredServices(base: { ingestion: string; incident: string; analysis: string }): MonitoredService[] {
  return [
    {
      id: 'ingestion-service',
      name: 'Ingestion Service',
      runtime: 'Java 21 · Spring Boot',
      description: 'Receives and stores log and error events and publishes them to Kafka.',
      healthPath: `${base.ingestion}/actuator/health/readiness`,
    },
    {
      id: 'incident-service',
      name: 'Incident Service',
      runtime: 'Java 21 · Spring Boot',
      description: 'Correlates error events from Kafka into incidents and serves them.',
      healthPath: `${base.incident}/actuator/health/readiness`,
    },
    {
      id: 'analysis-service',
      name: 'Analysis Service',
      runtime: 'Python · FastAPI',
      description: 'Analyzes incidents and suggests potential root causes with evidence.',
      healthPath: `${base.analysis}/health/readiness`,
    },
  ]
}

const DEFAULT_TIMEOUT_MS = 5000

function readReportedStatus(body: unknown): string | undefined {
  if (typeof body === 'object' && body !== null && 'status' in body) {
    const { status } = body as { status: unknown }
    return typeof status === 'string' ? status : undefined
  }
  return undefined
}

/**
 * Calls a service health endpoint. Spring Boot Actuator and the analysis service both
 * respond with `{"status": "UP" | "DOWN" | ...}`. A response without that shape (for
 * example a proxy error page) means the service itself could not be reached.
 */
export async function checkHealth(
  path: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<HealthResult> {
  const startedAt = performance.now()
  try {
    const response = await fetchImpl(path, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    })
    const latencyMs = Math.round(performance.now() - startedAt)
    const reported = readReportedStatus(await response.json().catch(() => null))
    const checkedAt = new Date()

    if (reported === undefined) {
      return {
        status: 'UNREACHABLE',
        checkedAt,
        httpStatus: response.status,
        error: `No health response (HTTP ${response.status})`,
      }
    }
    return {
      status: response.ok && reported === 'UP' ? 'UP' : 'DOWN',
      checkedAt,
      httpStatus: response.status,
      latencyMs,
    }
  } catch (error) {
    return {
      status: 'UNREACHABLE',
      checkedAt: new Date(),
      error: error instanceof Error ? error.message : String(error),
    }
  }
}
