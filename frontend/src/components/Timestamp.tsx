import { formatDateTime, formatDateTimeWithZone, formatRelative, isValidTimestamp } from '../utils/time'

interface TimestampProps {
  value: string | null | undefined
  /** relative: "5 minutes ago" (exact time on hover); exact: local date and time. */
  format?: 'relative' | 'exact'
}

export function Timestamp({ value, format = 'relative' }: TimestampProps) {
  if (!value || !isValidTimestamp(value)) return <span className="muted">—</span>
  return (
    <time dateTime={value} title={formatDateTimeWithZone(value)}>
      {format === 'relative' ? formatRelative(value) : formatDateTime(value)}
    </time>
  )
}
