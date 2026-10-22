import { AlertCircle, Loader2, RefreshCw, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { describeError } from '../utils/errors'

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="data-state" role="status">
      <Loader2 size={20} aria-hidden="true" className="spin" />
      <span>{label}</span>
    </div>
  )
}

export function ErrorState({ error, onRetry, compact = false }: { error: unknown; onRetry?: () => void; compact?: boolean }) {
  const { title, detail } = describeError(error)
  return (
    <div className={`data-state data-state--error ${compact ? 'data-state--compact' : ''}`} role="alert">
      <AlertCircle size={20} aria-hidden="true" />
      <div>
        <strong>{title}</strong>
        <p>{detail}</p>
      </div>
      {onRetry && (
        <button type="button" className="button button--small" onClick={onRetry}>
          <RefreshCw size={14} aria-hidden="true" />
          Retry
        </button>
      )}
    </div>
  )
}

export function EmptyState({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children?: ReactNode }) {
  return (
    <div className="empty-state">
      <Icon size={28} aria-hidden="true" />
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  )
}

/** A refresh error shown above data that is still displayed from an earlier response. */
export function StaleDataWarning({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { title } = describeError(error)
  return (
    <div className="stale-warning" role="status">
      <AlertCircle size={16} aria-hidden="true" />
      <span>Refresh failed ({title}). Showing the last data received.</span>
      <button type="button" className="text-button" onClick={onRetry}>
        Retry
      </button>
    </div>
  )
}
