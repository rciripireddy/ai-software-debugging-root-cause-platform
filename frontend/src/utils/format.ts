import type { Confidence, IncidentStatus, RootCauseCategory, Severity } from '../types/api'

export function shortFingerprint(fingerprint: string): string {
  return fingerprint.slice(0, 12)
}

export function shortId(id: string): string {
  return id.slice(0, 8)
}

export function formatCount(value: number): string {
  return new Intl.NumberFormat('en-US').format(value)
}

/** The simple class name of a qualified exception type: java.net.SocketTimeoutException → SocketTimeoutException. */
export function simpleExceptionName(exceptionType: string | null | undefined): string | null {
  if (!exceptionType) return null
  return exceptionType.split('.').pop() ?? exceptionType
}

export const SEVERITY_LABELS: Record<Severity, string> = {
  DEBUG: 'Debug',
  INFO: 'Info',
  WARN: 'Warning',
  ERROR: 'Error',
  FATAL: 'Fatal',
}

export const STATUS_LABELS: Record<IncidentStatus, string> = {
  OPEN: 'Open',
  INVESTIGATING: 'Investigating',
  RESOLVED: 'Resolved',
}

export const CONFIDENCE_LABELS: Record<Confidence, string> = {
  HIGH: 'High confidence',
  MEDIUM: 'Medium confidence',
  LOW: 'Low confidence',
}

export const CATEGORY_LABELS: Record<RootCauseCategory, string> = {
  APPLICATION_CODE: 'Application code',
  DOWNSTREAM_DEPENDENCY: 'Downstream dependency',
  DATABASE: 'Database / connectivity',
  TIMEOUT: 'Timeout',
  CONFIGURATION: 'Configuration',
  AUTHENTICATION: 'Authentication / authorization',
  RESOURCE_EXHAUSTION: 'Resource exhaustion',
  SERIALIZATION: 'Serialization / data format',
  UNKNOWN: 'Insufficient evidence',
}

/** How a candidate is introduced: only HIGH confidence earns "most likely". */
export function candidateLead(confidence: Confidence): string {
  return confidence === 'HIGH' ? 'Most likely cause based on available evidence' : 'Potential root cause'
}

export function categoryLabel(category: RootCauseCategory | null | undefined): string {
  return category ? (CATEGORY_LABELS[category] ?? category) : 'Not analyzed'
}
