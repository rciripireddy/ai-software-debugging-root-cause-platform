import type {
  AnalysisSummary,
  EventActivity,
  EventDetail,
  EventQuery,
  EventSummary,
  HealthResult,
  IncidentAnalysis,
  IncidentDetail,
  IncidentQuery,
  IncidentStats,
  IncidentSummary,
  Page,
  RegisteredApplication,
  RelatedIncident,
} from '../types/api'

export interface MonitoredService {
  id: 'ingestion-service' | 'incident-service' | 'analysis-service'
  name: string
  runtime: string
  description: string
  healthPath: string
}

/**
 * Everything the dashboard reads or triggers. Implemented by the HTTP client (real APIs) and by
 * the demo data source (bundled synthetic data); pages only depend on this interface.
 */
export interface DashboardApi {
  readonly mode: 'live' | 'demo'

  // ingestion-service
  listEvents(query: EventQuery, signal?: AbortSignal): Promise<Page<EventSummary>>
  getEvent(id: string, signal?: AbortSignal): Promise<EventDetail>
  eventActivity(hours: number, signal?: AbortSignal): Promise<EventActivity>
  listApplications(signal?: AbortSignal): Promise<RegisteredApplication[]>

  // incident-service
  listIncidents(query: IncidentQuery, signal?: AbortSignal): Promise<Page<IncidentSummary>>
  getIncident(id: string, signal?: AbortSignal): Promise<IncidentDetail>
  incidentStats(days: number, signal?: AbortSignal): Promise<IncidentStats>

  // analysis-service
  /** The latest stored analysis, or null if the incident has not been analyzed yet. */
  getLatestAnalysis(incidentId: string, signal?: AbortSignal): Promise<IncidentAnalysis | null>
  analyzeIncident(incidentId: string): Promise<IncidentAnalysis>
  analysisHistory(incidentId: string, signal?: AbortSignal): Promise<AnalysisSummary[]>
  /** Latest analysis per incident; all incidents (most recent first) when ids is undefined. */
  latestAnalyses(incidentIds: string[] | undefined, limit: number, signal?: AbortSignal): Promise<AnalysisSummary[]>
  relatedIncidents(incidentId: string, signal?: AbortSignal): Promise<RelatedIncident[]>

  // health
  services: MonitoredService[]
  checkHealth(service: MonitoredService): Promise<HealthResult>
}
