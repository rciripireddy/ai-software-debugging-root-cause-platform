import { useState } from 'react'

const COLLAPSED_LINES = 12

/** A stack trace in a scrollable monospace block, collapsed to its first lines when long. */
export function StackTrace({ trace, label = 'Stack trace' }: { trace: string; label?: string }) {
  const lines = trace.replace(/\r\n/g, '\n').split('\n')
  const long = lines.length > COLLAPSED_LINES
  const [expanded, setExpanded] = useState(false)
  const shown = long && !expanded ? lines.slice(0, COLLAPSED_LINES).join('\n') : lines.join('\n')

  return (
    <figure className="stack-trace">
      <figcaption>
        <span>{label}</span>
        <span className="muted">{lines.length} lines</span>
      </figcaption>
      <pre tabIndex={0} aria-label={label}>
        <code>{shown}</code>
      </pre>
      {long && (
        <button type="button" className="text-button" onClick={() => setExpanded((value) => !value)}>
          {expanded ? 'Show fewer lines' : `Show all ${lines.length} lines`}
        </button>
      )}
    </figure>
  )
}
