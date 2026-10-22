// Response types of the backend APIs used by the dashboard. Timestamps are ISO-8601 strings
// in UTC, exactly as the services return them.

export type Severity = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL'
export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'
export type Confidence = 'LOW' | 'MEDIUM' | 'HIGH'
export type RootCauseCategory =
  | 'APPLICATION_CODE'
  | 'DOWNSTREAM_DEPENDENCY'
  | 'DATABASE'
  | 'TIMEOUT'
  | 'CONFIGURATION'
  | 'AUTHENTICATION'
  | 'RESOURCE_EXHAUSTION'
  | 'SERIALIZATION'
  | 'UNKNOWN'

export const SEVERITIES: Severity[] = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']
export const INCIDENT_STATUSES: IncidentStatus[] = ['OPEN', 'INVESTIGATING', 'RESOLVED']

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface ApplicationRef {
  id: string
  name: string
  service: string
  environment: string
}

// ingestion-service

export interface EventSummary {
  id: string
  application: ApplicationRef
  timestamp: string
  severity: Severity
  message: string
  exceptionType: string | null
  source: string | null
  host: string | null
  traceId: string | null
  correlationId: string | null
  fingerprint: string
  hasStackTrace: boolean
  receivedAt: string
}

export interface EventDetail {
  id: string
  application: ApplicationRef
  timestamp: string
  severity: Severity
  message: string
  exceptionType: string | null
  stackTrace: string | null
  source: string | null
  host: string | null
  traceId: string | null
  correlationId: string | null
  fingerprint: string
  receivedAt: string
}

export interface EventActivity {
  since: string
  until: string
  totalEvents: number
  bySeverity: Record<Severity, number>
  byService: { service: string; events: number; errors: number }[]
}

export interface RegisteredApplication {
  id: string
  name: string
  service: string
  environment: string
  registeredAt: string
}

export interface EventQuery {
  application?: string
  service?: string
  environment?: string
  severity?: Severity
  q?: string
  page?: number
  size?: number
}

// incident-service

export interface IncidentSummary {
  id: string
  title: string
  status: IncidentStatus
  severity: Severity
  application: ApplicationRef
  occurrenceCount: number
  firstSeenAt: string
  lastSeenAt: string
  fingerprint: string
  createdAt: string
  updatedAt: string
}

export interface IncidentEvent {
  id: string
  timestamp: string
  severity: Severity
  message: string
  exceptionType: string | null
  host: string | null
  traceId: string | null
  correlationId: string | null
}

export interface IncidentDetail extends IncidentSummary {
  summary: string | null
  recentEventLimit: number
  recentEvents: IncidentEvent[]
}

export interface IncidentStats {
  totalIncidents: number
  byStatus: Record<IncidentStatus, number>
  unresolvedBySeverity: Record<Severity, number>
  servicesAffected: number
  topServices: { service: string; unresolvedIncidents: number; occurrences: number }[]
  newIncidentsPerDay: { date: string; count: number }[]
  generatedAt: string
}

export interface IncidentQuery {
  status?: IncidentStatus
  severity?: Severity
  application?: string
  service?: string
  environment?: string
  q?: string
  page?: number
  size?: number
}

// analysis-service

export interface Evidence {
  signal: string
  description: string
  matched: number | null
  total: number | null
}

export interface Candidate {
  rank: number
  category: RootCauseCategory
  title: string
  confidence: Confidence
  score: number
  maxScore: number
  confidenceBasis: string[]
  explanation: string
  evidence: Evidence[]
  affectedComponent: {
    service: string
    environment: string
    location: string | null
    source: string | null
  }
  relatedIncidentIds: string[]
  relatedEventIds: string[]
  limitations: string[]
}

export interface Share {
  value: string
  count: number
  total: number
}

export interface LinkedIncident {
  incidentId: string
  title: string
  service: string
  linkedEvents: number
  sharedTraceEvents: number
  medianGapSeconds: number
  basis: 'TRACE' | 'TIME'
}

export interface MatchingSignal {
  signal: string
  points: number
  description: string
}

export interface RelatedIncident {
  incidentId: string
  title: string
  status: IncidentStatus
  service: string
  environment: string
  score: number
  strength: 'STRONG' | 'PARTIAL'
  matchingSignals: MatchingSignal[]
}

export interface IncidentAnalysis {
  id: string
  revision: number
  incidentId: string
  analysisVersion: string
  generatedAt: string
  incident: {
    id: string
    title: string
    status: IncidentStatus
    severity: Severity
    application: string
    service: string
    environment: string
    occurrenceCount: number
    firstSeenAt: string
    lastSeenAt: string
    fingerprint: string
  }
  summary: string
  confidence: Confidence
  primaryCandidate: Candidate | null
  candidates: Candidate[]
  patterns: {
    recurrence: {
      pattern: 'SINGLE' | 'BURST' | 'RECURRING' | 'PERSISTENT'
      description: string
      occurrenceCount: number
      eventsAnalyzed: number
      firstSeenAt: string
      lastSeenAt: string
      spanSeconds: number
      windowSeconds: number
      maxOccurrencesInWindow: number
      medianIntervalSeconds: number | null
    }
    dominantException: Share | null
    topApplicationFrame: Share | null
    repeatedFrames: Share[]
    source: Share | null
    hosts: { distinct: number; top: Share[]; concentrated: boolean }
    precedingErrors: LinkedIncident[]
    subsequentErrors: LinkedIncident[]
  }
  relatedIncidents: RelatedIncident[]
  dataCoverage: {
    occurrenceCount: number
    eventsAnalyzed: number
    eventsWithDetails: number
    eventsWithStackTrace: number
    incidentsCompared: number
  }
  limitations: string[]
}

export interface AnalysisSummary {
  id: string
  incidentId: string
  /** Only in the latest-analyses list (not in the history of one incident). */
  application?: string
  service?: string
  environment?: string
  revision: number
  analysisVersion: string
  generatedAt: string
  confidence: Confidence
  primaryCategory: RootCauseCategory | null
  primaryTitle: string | null
  summary: string
}

// Service health (checked from the browser)

export type HealthStatus = 'UP' | 'DOWN' | 'UNREACHABLE'

export interface HealthResult {
  status: HealthStatus
  checkedAt: Date
  httpStatus?: number
  latencyMs?: number
  error?: string
}
