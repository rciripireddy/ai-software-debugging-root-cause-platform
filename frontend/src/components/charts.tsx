import { Link } from 'react-router'
import { formatCount } from '../utils/format'
import { formatDay } from '../utils/time'

// Two small single-series charts drawn with HTML/CSS. Each shows its values as text, so the
// numbers are readable without the bars and by screen readers.

export interface BarItem {
  label: string
  value: number
  /** Optional secondary text after the value, e.g. "12 occurrences". */
  note?: string
  href?: string
}

/** Horizontal bars, largest first as given; the value is printed at the end of each bar. */
export function BarList({ items, label }: { items: BarItem[]; label: string }) {
  const max = Math.max(...items.map((item) => item.value), 1)
  return (
    <ul className="bar-list" aria-label={label}>
      {items.map((item) => (
        <li key={item.label} title={`${item.label}: ${formatCount(item.value)}${item.note ? ` (${item.note})` : ''}`}>
          <span className="bar-list__label">
            {item.href ? <Link to={item.href}>{item.label}</Link> : item.label}
          </span>
          <span className="bar-list__track" aria-hidden="true">
            <span className="bar-list__bar" style={{ width: `${(item.value / max) * 100}%` }} />
          </span>
          <span className="bar-list__value">
            {formatCount(item.value)}
            {item.note && <span className="muted"> · {item.note}</span>}
          </span>
        </li>
      ))}
    </ul>
  )
}

export interface DayValue {
  date: string // YYYY-MM-DD
  count: number
}

/**
 * Columns per day. Only the largest value is labelled on the chart; every value is in the
 * hover title and in the data table below the chart.
 */
export function DailyColumns({ days, label, valueLabel }: { days: DayValue[]; label: string; valueLabel: string }) {
  const max = Math.max(...days.map((day) => day.count), 0)
  const peak = days.reduce<DayValue | undefined>((best, day) => (!best || day.count > best.count ? day : best), undefined)
  const labelEvery = days.length > 10 ? Math.ceil(days.length / 7) : 1
  return (
    <figure className="columns-chart">
      <div className="columns-chart__plot" role="img" aria-label={`${label}. Peak ${peak ? `${formatCount(peak.count)} on ${formatDay(peak.date)}` : 'none'}.`}>
        {days.map((day, index) => (
          <div key={day.date} className="columns-chart__slot" title={`${formatDay(day.date)}: ${formatCount(day.count)} ${valueLabel}`}>
            <div className="columns-chart__column-area">
              {max > 0 && day === peak && day.count > 0 && (
                <span className="columns-chart__peak">{formatCount(day.count)}</span>
              )}
              <div
                className={`columns-chart__column ${day.count === 0 ? 'columns-chart__column--zero' : ''}`}
                style={{ height: max > 0 ? `${Math.max((day.count / max) * 100, day.count > 0 ? 3 : 0)}%` : '0%' }}
              />
            </div>
            <span className="columns-chart__axis-label" aria-hidden="true">
              {/* Counted from the newest day, so it is always labelled and never crowded. */}
              {(days.length - 1 - index) % labelEvery === 0 ? formatDay(day.date) : ''}
            </span>
          </div>
        ))}
      </div>
      <details className="chart-table">
        <summary>Show data table</summary>
        <table>
          <caption className="visually-hidden">{label}</caption>
          <thead>
            <tr>
              <th scope="col">Day (UTC)</th>
              <th scope="col">{valueLabel}</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.date}>
                <td>{formatDay(day.date)}</td>
                <td>{formatCount(day.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  )
}
