export type HealthStatus = 'UP' | 'DOWN' | 'UNREACHABLE'

export interface MonitoredService {
  id: string
  name: string
  runtime: string
  description: string
  healthPath: string
}

export interface HealthResult {
  status: HealthStatus
  checkedAt: Date
  httpStatus?: number
  latencyMs?: number
  error?: string
}

// Paths are relative so they work behind the Vite dev proxy and the nginx proxy in Docker.
export const MONITORED_SERVICES: MonitoredService[] = [
  {
    id: 'ingestion-service',
    name: 'Ingestion Service',
    runtime: 'Java 21 · Spring Boot',
    description: 'Will receive application logs and error events.',
    healthPath: '/api/ingestion/actuator/health',
  },
  {
    id: 'incident-service',
    name: 'Incident Service',
    runtime: 'Java 21 · Spring Boot',
    description: 'Will group related errors into incidents.',
    healthPath: '/api/incident/actuator/health',
  },
  {
    id: 'analysis-service',
    name: 'Analysis Service',
    runtime: 'Python · FastAPI',
    description: 'Will analyze incidents for likely root causes.',
    healthPath: '/api/analysis/health',
  },
]

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
