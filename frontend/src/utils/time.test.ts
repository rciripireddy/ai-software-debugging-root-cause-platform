import { describe, expect, it } from 'vitest'
import { formatDateTime, formatDateTimeWithZone, formatDay, formatDuration, formatRelative, isValidTimestamp } from './time'

const NOW = new Date('2026-10-22T12:00:00Z')

describe('formatRelative', () => {
  it.each([
    ['2026-10-22T11:59:30Z', 'just now'],
    ['2026-10-22T11:55:00Z', '5 minutes ago'],
    ['2026-10-22T09:00:00Z', '3 hours ago'],
    ['2026-10-21T12:00:00Z', '1 day ago'],
    ['2026-10-14T10:02:11.204Z', '1 week ago'],
    ['2026-08-20T12:00:00Z', '2 months ago'],
    ['2026-10-22T14:00:00Z', 'in 2 hours'],
  ])('formats %s as "%s"', (value, expected) => {
    expect(formatRelative(value, NOW)).toBe(expected)
  })

  it('handles invalid values', () => {
    expect(formatRelative('not a date', NOW)).toBe('—')
  })
})

describe('exact timestamps', () => {
  it('converts UTC timestamps to the requested time zone', () => {
    expect(formatDateTime('2026-10-14T10:02:11.204Z', { timeZone: 'UTC' })).toBe('Oct 14, 2026, 10:02:11 AM')
    expect(formatDateTime('2026-10-14T10:02:11.204Z', { timeZone: 'Asia/Kolkata' })).toBe('Oct 14, 2026, 3:32:11 PM')
    expect(formatDateTime('2026-10-14T23:30:00Z', { timeZone: 'America/New_York' })).toBe('Oct 14, 2026, 7:30:00 PM')
  })

  it('includes the time zone name in tooltips', () => {
    expect(formatDateTimeWithZone('2026-10-14T10:02:11Z', { timeZone: 'UTC' })).toBe('Oct 14, 2026, 10:02:11 AM UTC')
  })

  it('formats UTC calendar days without shifting them to another date', () => {
    expect(formatDay('2026-10-14')).toBe('Oct 14')
  })

  it('rejects invalid timestamps', () => {
    expect(isValidTimestamp('2026-10-14T10:02:11Z')).toBe(true)
    expect(isValidTimestamp('yesterday')).toBe(false)
    expect(isValidTimestamp(null)).toBe(false)
    expect(formatDateTime('yesterday')).toBe('—')
  })
})

describe('formatDuration', () => {
  it.each([
    [12, '12 s'],
    [651, '11 min'],
    [5 * 3600, '5 h'],
    [3 * 86400, '3 d'],
  ])('formats %d seconds as %s', (seconds, expected) => {
    expect(formatDuration(seconds)).toBe(expected)
  })
})
