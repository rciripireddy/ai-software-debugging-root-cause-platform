import { RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'
import { useApi } from '../api/ApiContext'
import { ModeBadge } from './Badges'
import { Timestamp } from './Timestamp'

interface PageHeaderProps {
  title: string
  description: string
  actions?: ReactNode
  /** When given, a refresh button and the time of the last update are shown. */
  onRefresh?: () => void
  refreshing?: boolean
  updatedAt?: Date
}

export function PageHeader({ title, description, actions, onRefresh, refreshing = false, updatedAt }: PageHeaderProps) {
  const { mode } = useApi()
  return (
    <header className="page-header">
      <div>
        <div className="page-header__title-row">
          <h1>{title}</h1>
          <ModeBadge mode={mode} />
        </div>
        <p className="page-header__description">{description}</p>
      </div>
      <div className="page-header__actions">
        {updatedAt && (
          <span className="muted page-header__updated">
            Updated <Timestamp value={updatedAt.toISOString()} />
          </span>
        )}
        {actions}
        {onRefresh && (
          <button type="button" className="button" onClick={onRefresh} disabled={refreshing}>
            <RefreshCw size={16} aria-hidden="true" className={refreshing ? 'spin' : undefined} />
            Refresh
          </button>
        )}
      </div>
    </header>
  )
}
