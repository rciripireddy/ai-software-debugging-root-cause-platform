import type {
  AnalysisSummary,
  EventActivity,
  EventDetail,
  EventSummary,
  IncidentAnalysis,
  IncidentDetail,
  IncidentStats,
  IncidentSummary,
  RegisteredApplication,
  RelatedIncident,
} from '../types/api'
import type { DashboardApi } from './dashboardApi'
import { checkHealth, monitoredServices } from './health'
import { ApiError, createHttpClient, hasFields, isItems, isPage, withQuery } from './http'

const isEvent = (item: unknown) => hasFields(item, 'id', 'application', 'timestamp', 'severity', 'message', 'fingerprint')
const isIncident = (item: unknown) =>
  hasFields(item, 'id', 'title', 'status', 'severity', 'application', 'occurrenceCount', 'lastSeenAt')
const isAnalysisSummary = (item: unknown) => hasFields(item, 'incidentId', 'revision', 'confidence', 'generatedAt')

const isEventDetail = (body: unknown): body is EventDetail => isEvent(body) && hasFields(body, 'stackTrace')
const isIncidentDetail = (body: unknown): body is IncidentDetail =>
  isIncident(body) && hasFields(body, 'recentEvents') && Array.isArray(body.recentEvents)
const isActivity = (body: unknown): body is EventActivity =>
  hasFields(body, 'totalEvents', 'bySeverity', 'byService') && Array.isArray(body.byService)
const isStats = (body: unknown): body is IncidentStats =>
  hasFields(body, 'totalIncidents', 'byStatus', 'unresolvedBySeverity', 'topServices', 'newIncidentsPerDay')
const isAnalysis = (body: unknown): body is IncidentAnalysis =>
  hasFields(body, 'id', 'incidentId', 'revision', 'candidates', 'patterns', 'relatedIncidents', 'limitations')
const isHistory = (body: unknown): body is { items: AnalysisSummary[] } =>
  hasFields(body, 'items') && Array.isArray(body.items)
const isRelated = (body: unknown): body is { items: RelatedIncident[] } =>
  hasFields(body, 'items', 'threshold') && Array.isArray(body.items)

export interface HttpApiOptions {
  fetchImpl?: typeof fetch
  timeoutMs: number
  base: { ingestion: string; incident: string; analysis: string }
}

/** The dashboard API backed by the real services, through the dev-server or nginx proxy. */
export function createHttpApi({ fetchImpl = (...args) => fetch(...args), timeoutMs, base }: HttpApiOptions): DashboardApi {
  const http = createHttpClient(fetchImpl, timeoutMs)
  const ingestion = (path: string) => `${base.ingestion}${path}`
  const incident = (path: string) => `${base.incident}${path}`
  const analysis = (path: string) => `${base.analysis}${path}`

  return {
    mode: 'live',

    listEvents: ({ page = 0, size = 25, ...filters }, signal) =>
      http.request('ingestion-service', withQuery(ingestion('/api/v1/logs'), { ...filters, page, size }),
        isPage<EventSummary>(isEvent), { signal }),

    getEvent: (id, signal) =>
      http.request('ingestion-service', ingestion(`/api/v1/logs/${encodeURIComponent(id)}`), isEventDetail, { signal }),

    eventActivity: (hours, signal) =>
      http.request('ingestion-service', withQuery(ingestion('/api/v1/logs/activity'), { hours }), isActivity, { signal }),

    listApplications: async (signal) =>
      (await http.request('ingestion-service', ingestion('/api/v1/applications'),
        isItems<RegisteredApplication>((item) => hasFields(item, 'name', 'service', 'environment')), { signal })).items,

    listIncidents: ({ page = 0, size = 20, ...filters }, signal) =>
      http.request('incident-service', withQuery(incident('/api/v1/incidents'), { ...filters, page, size }),
        isPage<IncidentSummary>(isIncident), { signal }),

    getIncident: (id, signal) =>
      http.request('incident-service', incident(`/api/v1/incidents/${encodeURIComponent(id)}`), isIncidentDetail,
        { signal }),

    incidentStats: (days, signal) =>
      http.request('incident-service', withQuery(incident('/api/v1/incidents/stats'), { days }), isStats, { signal }),

    getLatestAnalysis: async (incidentId, signal) => {
      try {
        return await http.request('analysis-service',
          analysis(`/api/v1/analysis/incidents/${encodeURIComponent(incidentId)}`), isAnalysis, { signal })
      } catch (error) {
        if (error instanceof ApiError && error.isNotFound) return null
        throw error
      }
    },

    analyzeIncident: (incidentId) =>
      http.request('analysis-service', analysis(`/api/v1/analysis/incidents/${encodeURIComponent(incidentId)}`),
        isAnalysis, { method: 'POST' }),

    analysisHistory: async (incidentId, signal) => {
      try {
        return (await http.request('analysis-service',
          analysis(`/api/v1/analysis/incidents/${encodeURIComponent(incidentId)}/history`), isHistory,
          { signal })).items
      } catch (error) {
        if (error instanceof ApiError && error.isNotFound) return []
        throw error
      }
    },

    latestAnalyses: async (incidentIds, limit, signal) => {
      if (incidentIds !== undefined && incidentIds.length === 0) return []
      return (await http.request('analysis-service',
        withQuery(analysis('/api/v1/analysis/incidents'), { incidentId: incidentIds, limit }),
        isItems<AnalysisSummary>(isAnalysisSummary), { signal })).items
    },

    relatedIncidents: async (incidentId, signal) =>
      (await http.request('analysis-service',
        analysis(`/api/v1/analysis/incidents/${encodeURIComponent(incidentId)}/related`), isRelated, { signal })).items,

    services: monitoredServices(base),
    checkHealth: (service) => checkHealth(service.healthPath, fetchImpl),
  }
}
