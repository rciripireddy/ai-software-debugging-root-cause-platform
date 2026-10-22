import { ChevronDown, ChevronRight } from 'lucide-react'
import type { ReactNode } from 'react'

// Secondary information stays in the page (and in the DOM) but collapsed, so the first view
// shows only what matters most. Both use native <details>, which is keyboard accessible.

/** An inline "Show more" section inside a card, e.g. technical details or the rest of a list. */
export function Disclosure({ label = 'Technical details', children }: { label?: string; children: ReactNode }) {
  return (
    <details className="disclosure">
      <summary>
        <ChevronRight size={16} aria-hidden="true" className="disclosure__icon" />
        {label}
      </summary>
      <div className="disclosure__body">{children}</div>
    </details>
  )
}

/** A whole card that starts collapsed to its heading and a one-line hint. */
export function CollapsibleCard({
  id,
  title,
  hint,
  children,
}: {
  id: string
  title: string
  hint?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="card card--collapsible" aria-labelledby={id}>
      <details>
        <summary>
          <span className="card--collapsible__heading">
            <h2 id={id}>{title}</h2>
            {hint && <span className="muted">{hint}</span>}
          </span>
          <ChevronDown size={18} aria-hidden="true" className="card--collapsible__icon" />
        </summary>
        <div className="card--collapsible__body">{children}</div>
      </details>
    </section>
  )
}
