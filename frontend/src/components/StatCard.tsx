import type { ReactNode } from 'react'
import { formatCount } from '../utils/format'

interface StatCardProps {
  label: string
  value: number | undefined
  /** One line explaining what is counted. */
  description: ReactNode
  /** Shown instead of the value when the source of the figure failed. */
  unavailable?: boolean
}

export function StatCard({ label, value, description, unavailable = false }: StatCardProps) {
  return (
    <article className="card metric-card" aria-label={label}>
      <h2>{label}</h2>
      {unavailable ? (
        <p className="metric-card__value metric-card__value--unavailable">Unavailable</p>
      ) : value === undefined ? (
        <p className="metric-card__value metric-card__value--loading" aria-label="Loading">
          …
        </p>
      ) : (
        <p className="metric-card__value">{formatCount(value)}</p>
      )}
      <p className="muted">{description}</p>
    </article>
  )
}
