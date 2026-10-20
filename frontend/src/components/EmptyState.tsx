import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

interface EmptyStateProps {
  icon: LucideIcon
  title: string
  children: ReactNode
}

export function EmptyState({ icon: Icon, title, children }: EmptyStateProps) {
  return (
    <div className="empty-state">
      <Icon size={28} aria-hidden="true" />
      <h3>{title}</h3>
      <p>{children}</p>
    </div>
  )
}
