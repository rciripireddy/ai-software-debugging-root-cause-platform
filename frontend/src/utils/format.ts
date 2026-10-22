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

// A qualified type ("java.net.SocketTimeoutException") or a bare one ending like an exception class.
const TITLE_EXCEPTION = /^((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*|[A-Z][\w$]*(?:Exception|Error|Throwable)): (.+)$/s

/**
 * Splits a default incident title ("<exceptionType>: <message>") into the message, shown as the
 * headline, and the exception type. Titles of any other shape are returned whole.
 */
export function splitIncidentTitle(title: string): { headline: string; exceptionType: string | null } {
  const match = TITLE_EXCEPTION.exec(title)
  return match ? { headline: match[2], exceptionType: match[1] } : { headline: title, exceptionType: null }
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
