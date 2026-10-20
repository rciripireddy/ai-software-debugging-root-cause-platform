export type FeatureStatus = 'live' | 'preview'

const LABELS: Record<FeatureStatus, string> = {
  live: 'Live',
  preview: 'Preview',
}

const DESCRIPTIONS: Record<FeatureStatus, string> = {
  live: 'Connected to running backend services',
  preview: 'Starter layout, not yet connected to backend data',
}

export function FeatureStatusBadge({ status }: { status: FeatureStatus }) {
  return (
    <span className={`feature-badge feature-badge--${status}`} title={DESCRIPTIONS[status]}>
      {LABELS[status]}
    </span>
  )
}
