// Timestamps from the APIs are UTC ISO-8601 strings. They are shown in the browser's time zone
// (or the zone passed in, for tests) as relative ("5 minutes ago") and exact values.

export interface TimeFormatOptions {
  locale?: string
  timeZone?: string
}

function toDate(value: string | Date): Date {
  return value instanceof Date ? value : new Date(value)
}

export function isValidTimestamp(value: string | Date | null | undefined): boolean {
  return value !== null && value !== undefined && !Number.isNaN(toDate(value).getTime())
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

/** "just now", "5 minutes ago", "in 2 hours", "3 days ago". */
export function formatRelative(value: string | Date, now: Date = new Date(), { locale }: TimeFormatOptions = {}): string {
  if (!isValidTimestamp(value)) return '—'
  const seconds = Math.round((toDate(value).getTime() - now.getTime()) / 1000)
  if (Math.abs(seconds) < 45) return 'just now'
  const format = new Intl.RelativeTimeFormat(locale ?? 'en', { numeric: 'always' })
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit)
  }
  return format.format(Math.round(seconds / 60), 'minute')
}

/** Exact local date and time, e.g. "Oct 14, 2026, 10:02:11 AM". */
export function formatDateTime(value: string | Date, { locale, timeZone }: TimeFormatOptions = {}): string {
  if (!isValidTimestamp(value)) return '—'
  return new Intl.DateTimeFormat(locale ?? 'en-US', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone,
  }).format(toDate(value))
}

/** Exact local date and time with the time zone name, for tooltips. */
export function formatDateTimeWithZone(value: string | Date, { locale, timeZone }: TimeFormatOptions = {}): string {
  if (!isValidTimestamp(value)) return '—'
  return new Intl.DateTimeFormat(locale ?? 'en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
    timeZone,
  }).format(toDate(value))
}

/** A calendar date given as "YYYY-MM-DD" (UTC day from the API), e.g. "Oct 14". */
export function formatDay(isoDate: string, { locale }: TimeFormatOptions = {}): string {
  const date = new Date(`${isoDate}T00:00:00Z`)
  if (Number.isNaN(date.getTime())) return isoDate
  return new Intl.DateTimeFormat(locale ?? 'en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(date)
}

/** A duration in seconds, e.g. "45 s", "11 min", "5 h", "2 d". */
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  if (seconds < 60) return `${seconds} s`
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`
  if (seconds < 172800) return `${Math.round(seconds / 3600)} h`
  return `${Math.round(seconds / 86400)} d`
}
