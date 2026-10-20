import type { ReactNode } from 'react'
import { FeatureStatusBadge, type FeatureStatus } from './FeatureStatusBadge'

interface PageHeaderProps {
  title: string
  description: string
  status: FeatureStatus
  actions?: ReactNode
}

export function PageHeader({ title, description, status, actions }: PageHeaderProps) {
  return (
    <header className="page-header">
      <div>
        <div className="page-header__title-row">
          <h1>{title}</h1>
          <FeatureStatusBadge status={status} />
        </div>
        <p className="page-header__description">{description}</p>
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </header>
  )
}
