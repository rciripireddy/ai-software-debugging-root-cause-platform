import { AlertOctagon, AlertTriangle, CheckCircle2, CircleDot, Info, Search } from 'lucide-react'
import type { Confidence, IncidentStatus, RootCauseCategory, Severity } from '../types/api'
import { categoryLabel, CONFIDENCE_LABELS, SEVERITY_LABELS, STATUS_LABELS } from '../utils/format'

// Every badge shows its meaning as text (and an icon where useful), never by color alone.

const SEVERITY_ICONS: Partial<Record<Severity, typeof Info>> = {
  WARN: AlertTriangle,
  ERROR: AlertTriangle,
  FATAL: AlertOctagon,
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  const Icon = SEVERITY_ICONS[severity]
  return (
    <span className={`badge badge--severity-${severity.toLowerCase()}`}>
      {Icon && <Icon size={12} aria-hidden="true" />}
      {SEVERITY_LABELS[severity] ?? severity}
    </span>
  )
}

const STATUS_ICONS: Record<IncidentStatus, typeof Info> = {
  OPEN: CircleDot,
  INVESTIGATING: Search,
  RESOLVED: CheckCircle2,
}

export function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  const Icon = STATUS_ICONS[status] ?? CircleDot
  return (
    <span className={`badge badge--status-${status.toLowerCase()}`}>
      <Icon size={12} aria-hidden="true" />
      {STATUS_LABELS[status] ?? status}
    </span>
  )
}

/** Confidence band; the bars (1–3 filled) repeat the band for readers who skim. */
export function ConfidenceBadge({ confidence, compact = false }: { confidence: Confidence; compact?: boolean }) {
  const level = confidence === 'HIGH' ? 3 : confidence === 'MEDIUM' ? 2 : 1
  return (
    <span className={`badge badge--confidence-${confidence.toLowerCase()}`}>
      <span className="confidence-bars" aria-hidden="true">
        {[1, 2, 3].map((bar) => (
          <span key={bar} className={bar <= level ? 'confidence-bars__on' : undefined} />
        ))}
      </span>
      {compact ? confidence : CONFIDENCE_LABELS[confidence]}
    </span>
  )
}

export function CategoryTag({ category }: { category: RootCauseCategory | null | undefined }) {
  return <span className="tag">{categoryLabel(category)}</span>
}

export type DataMode = 'live' | 'demo'

export function ModeBadge({ mode }: { mode: DataMode }) {
  return (
    <span
      className={`feature-badge feature-badge--${mode}`}
      title={mode === 'live' ? 'Data from the running backend services' : 'Bundled synthetic demo data, not live'}
    >
      {mode === 'live' ? 'Live' : 'Demo data'}
    </span>
  )
}
