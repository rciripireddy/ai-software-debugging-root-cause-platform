import { vi } from 'vitest'
import type { DashboardApi } from '../api/dashboardApi'
import { monitoredServices } from '../api/health'
import { ApiError, type ServiceName } from '../api/http'
import { analysis, analysisSummary, eventDetail, eventSummary, incidentDetail, incidentSummary, page, stats } from './fixtures'

/** A DashboardApi whose methods are vi.fn mocks with successful defaults; override per test. */
export function createFakeApi(overrides: Partial<DashboardApi> = {}) {
  const api = {
    mode: 'live' as const,
    listEvents: vi.fn(async () => page([eventSummary()], 0, 25)),
    getEvent: vi.fn(async () => eventDetail()),
    eventActivity: vi.fn(async () => ({
      since: '2026-10-21T15:00:00Z',
      until: '2026-10-22T15:00:00Z',
      totalEvents: 51,
      bySeverity: { DEBUG: 0, INFO: 2, WARN: 1, ERROR: 44, FATAL: 4 },
      byService: [
        { service: 'payment-service', events: 13, errors: 12 },
        { service: 'checkout-service', events: 7, errors: 6 },
      ],
    })),
    listApplications: vi.fn(async () => [
      { id: 'a1', name: 'demo-store', service: 'payment-service', environment: 'staging', registeredAt: '2026-10-22T14:47:00Z' },
      { id: 'a2', name: 'demo-store', service: 'checkout-service', environment: 'staging', registeredAt: '2026-10-22T14:47:00Z' },
      { id: 'a3', name: 'demo-store', service: 'payment-service', environment: 'production', registeredAt: '2026-10-22T14:47:00Z' },
    ]),
    listIncidents: vi.fn(async () => page([incidentSummary()])),
    getIncident: vi.fn(async () => incidentDetail()),
    incidentStats: vi.fn(async () => stats()),
    getLatestAnalysis: vi.fn(async () => analysis()),
    analyzeIncident: vi.fn(async () => analysis({ revision: 2 })),
    analysisHistory: vi.fn(async () => [analysisSummary()]),
    latestAnalyses: vi.fn(async () => [analysisSummary()]),
    relatedIncidents: vi.fn(async () => analysis().relatedIncidents),
    services: monitoredServices({ ingestion: '/api/ingestion', incident: '/api/incident', analysis: '/api/analysis' }),
    checkHealth: vi.fn(async () => ({ status: 'UP' as const, checkedAt: new Date(), httpStatus: 200, latencyMs: 12 })),
    ...overrides,
  }
  return api satisfies DashboardApi
}

export function unavailable(service: ServiceName): ApiError {
  return new ApiError({
    kind: 'unavailable',
    service,
    title: `${service} unavailable`,
    detail: `${service} could not be reached.`,
  })
}

export function notFound(service: ServiceName, detail = 'Not found.'): ApiError {
  return new ApiError({ kind: 'http', service, status: 404, title: 'Resource not found', detail })
}
