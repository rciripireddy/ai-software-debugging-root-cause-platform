import type { DashboardApi } from '../api/dashboardApi'
import { monitoredServices } from '../api/health'
import { ApiError } from '../api/http'
import {
  INCIDENT_STATUSES,
  SEVERITIES,
  type AnalysisSummary,
  type EventDetail,
  type EventSummary,
  type HealthStatus,
  type IncidentAnalysis,
  type IncidentDetail,
  type IncidentSummary,
  type IncidentStatus,
  type Page,
  type RegisteredApplication,
  type RelatedIncident,
  type Severity,
} from '../types/api'

/**
 * Synthetic demo data (see demo/README.md): real responses of the platform's APIs, captured from
 * a local run on the synthetic scenarios in sample-data/. Never live data.
 */
export interface DemoDataset {
  description: string
  capturedAt: string
  applications: RegisteredApplication[]
  events: EventDetail[]
  incidents: IncidentDetail[]
  analyses: Record<string, IncidentAnalysis>
  history: Record<string, AnalysisSummary[]>
  related: Record<string, RelatedIncident[]>
  health: Record<string, { status: HealthStatus; httpStatus?: number; latencyMs?: number }>
}

function page<T>(items: T[], pageNumber = 0, size = 20): Page<T> {
  return {
    items: items.slice(pageNumber * size, pageNumber * size + size),
    page: pageNumber,
    size,
    totalItems: items.length,
    totalPages: Math.ceil(items.length / size),
  }
}

const lower = (value: string | undefined) => value?.trim().toLowerCase() || undefined

function notFound(service: 'ingestion-service' | 'incident-service', what: string): ApiError {
  return new ApiError({ kind: 'http', service, status: 404, title: 'Resource not found', detail: `${what} was not found.` })
}

function toIncidentSummary(incident: IncidentDetail): IncidentSummary {
  const { id, title, status, severity, application, occurrenceCount, firstSeenAt, lastSeenAt, fingerprint } = incident
  return { id, title, status, severity, application, occurrenceCount, firstSeenAt, lastSeenAt, fingerprint,
    createdAt: incident.createdAt, updatedAt: incident.updatedAt }
}

function summarize(analysis: IncidentAnalysis): AnalysisSummary {
  return {
    id: analysis.id,
    incidentId: analysis.incidentId,
    application: analysis.incident.application,
    service: analysis.incident.service,
    environment: analysis.incident.environment,
    revision: analysis.revision,
    analysisVersion: analysis.analysisVersion,
    generatedAt: analysis.generatedAt,
    confidence: analysis.confidence,
    primaryCategory: analysis.primaryCandidate?.category ?? null,
    primaryTitle: analysis.primaryCandidate?.title ?? null,
    summary: analysis.summary,
  }
}

/** The dashboard API over the bundled demo dataset. Makes no network requests. */
export function createDemoApi(data: DemoDataset): DashboardApi {
  const capturedAt = new Date(data.capturedAt)
  const byTimeDesc = <T>(time: (item: T) => string) => (a: T, b: T) => time(b).localeCompare(time(a))
  const events = [...data.events].sort(byTimeDesc((e) => e.timestamp))
  const incidents = [...data.incidents].sort(byTimeDesc((i) => i.lastSeenAt))
  const incidentById = new Map(incidents.map((incident) => [incident.id, incident]))
  const eventById = new Map(events.map((event) => [event.id, event]))
  const services = monitoredServices({ ingestion: '/api/ingestion', incident: '/api/incident', analysis: '/api/analysis' })

  return {
    mode: 'demo',

    async listEvents({ q, application, service, environment, severity, page: number = 0, size = 25 }) {
      const text = lower(q)
      const matches = events.filter(
        (e) =>
          (!application || e.application.name === lower(application)) &&
          (!service || e.application.service === lower(service)) &&
          (!environment || e.application.environment === lower(environment)) &&
          (!severity || e.severity === severity) &&
          (!text || e.message.toLowerCase().includes(text) || (e.exceptionType ?? '').toLowerCase().includes(text)),
      )
      return page<EventSummary>(
        matches.map(({ stackTrace, ...summary }) => ({ ...summary, hasStackTrace: stackTrace !== null })),
        number,
        size,
      )
    },

    async getEvent(id) {
      const event = eventById.get(id)
      if (!event) throw notFound('ingestion-service', `Log event ${id}`)
      return event
    },

    async eventActivity(hours) {
      const since = new Date(capturedAt.getTime() - hours * 3600_000)
      const recent = events.filter((e) => new Date(e.receivedAt) >= since)
      const bySeverity = Object.fromEntries(SEVERITIES.map((s) => [s, 0])) as Record<Severity, number>
      const byService = new Map<string, { service: string; events: number; errors: number }>()
      for (const e of recent) {
        bySeverity[e.severity] += 1
        const row = byService.get(e.application.service) ?? { service: e.application.service, events: 0, errors: 0 }
        row.events += 1
        if (e.severity === 'ERROR' || e.severity === 'FATAL') row.errors += 1
        byService.set(row.service, row)
      }
      return {
        since: since.toISOString(),
        until: capturedAt.toISOString(),
        totalEvents: recent.length,
        bySeverity,
        byService: [...byService.values()].sort((a, b) => b.events - a.events || a.service.localeCompare(b.service)),
      }
    },

    async listApplications() {
      return data.applications
    },

    async listIncidents({ q, status, severity, application, service, environment, page: number = 0, size = 20 }) {
      const text = lower(q)
      const matches = incidents.filter(
        (i) =>
          (!status || i.status === status) &&
          (!severity || i.severity === severity) &&
          (!application || i.application.name === lower(application)) &&
          (!service || i.application.service === lower(service)) &&
          (!environment || i.application.environment === lower(environment)) &&
          (!text || i.title.toLowerCase().includes(text)),
      )
      return page(matches.map(toIncidentSummary), number, size)
    },

    async getIncident(id) {
      const incident = incidentById.get(id)
      if (!incident) throw notFound('incident-service', `Incident ${id}`)
      return incident
    },

    async incidentStats(days) {
      const byStatus = Object.fromEntries(INCIDENT_STATUSES.map((s) => [s, 0])) as Record<IncidentStatus, number>
      const unresolvedBySeverity = Object.fromEntries(SEVERITIES.map((s) => [s, 0])) as Record<Severity, number>
      const perService = new Map<string, { service: string; unresolvedIncidents: number; occurrences: number }>()
      for (const i of incidents) {
        byStatus[i.status] += 1
        if (i.status === 'RESOLVED') continue
        unresolvedBySeverity[i.severity] += 1
        const row = perService.get(i.application.service) ?? { service: i.application.service, unresolvedIncidents: 0, occurrences: 0 }
        row.unresolvedIncidents += 1
        row.occurrences += i.occurrenceCount
        perService.set(row.service, row)
      }
      const today = new Date(Date.UTC(capturedAt.getUTCFullYear(), capturedAt.getUTCMonth(), capturedAt.getUTCDate()))
      const newIncidentsPerDay = Array.from({ length: days }, (_, index) => {
        const day = new Date(today.getTime() - (days - 1 - index) * 86400_000).toISOString().slice(0, 10)
        return { date: day, count: incidents.filter((i) => i.firstSeenAt.slice(0, 10) === day).length }
      })
      return {
        totalIncidents: incidents.length,
        byStatus,
        unresolvedBySeverity,
        servicesAffected: perService.size,
        topServices: [...perService.values()]
          .sort((a, b) => b.unresolvedIncidents - a.unresolvedIncidents || b.occurrences - a.occurrences || a.service.localeCompare(b.service))
          .slice(0, 8),
        newIncidentsPerDay,
        generatedAt: capturedAt.toISOString(),
      }
    },

    async getLatestAnalysis(incidentId) {
      return data.analyses[incidentId] ?? null
    },

    async analyzeIncident(incidentId) {
      const analysis = data.analyses[incidentId]
      if (!analysis) {
        throw new ApiError({
          kind: 'http',
          service: 'analysis-service',
          status: 404,
          title: 'Not available in demo mode',
          detail: 'The demo data contains no analysis for this incident, and demo mode cannot run new analyses.',
        })
      }
      return analysis
    },

    async analysisHistory(incidentId) {
      return data.history[incidentId] ?? []
    },

    async latestAnalyses(incidentIds, limit) {
      const all = Object.values(data.analyses).map(summarize)
      const selected = incidentIds ? all.filter((a) => incidentIds.includes(a.incidentId)) : all
      return selected.sort(byTimeDesc((a) => a.generatedAt)).slice(0, limit)
    },

    async relatedIncidents(incidentId) {
      return data.related[incidentId] ?? []
    },

    services,
    async checkHealth(service) {
      const snapshot = data.health[service.id]
      return snapshot
        ? { ...snapshot, checkedAt: capturedAt }
        : { status: 'UNREACHABLE', checkedAt: capturedAt, error: 'Not part of the demo snapshot' }
    },
  }
}
