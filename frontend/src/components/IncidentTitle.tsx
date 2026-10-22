import { Fragment } from 'react'
import { Link } from 'react-router'
import { simpleExceptionName, splitIncidentTitle } from '../utils/format'

/**
 * An incident title as a readable headline (the error message) with the exception's simple class
 * name beneath it. The complete title is kept in the hover text.
 */
export function IncidentTitle({ title, to, showType = true }: { title: string; to?: string; showType?: boolean }) {
  const { headline, exceptionType } = splitIncidentTitle(title)
  return (
    <span className="incident-title" title={title}>
      {to ? (
        <Link to={to} className="incident-title__headline">
          {headline}
        </Link>
      ) : (
        <span className="incident-title__headline">{headline}</span>
      )}
      {showType && exceptionType && (
        <span className="incident-title__type">
          {(simpleExceptionName(exceptionType) ?? '').split('$').map((part, index) => (
            <Fragment key={index}>
              {index > 0 && (
                <>
                  $<wbr />
                </>
              )}
              {part}
            </Fragment>
          ))}
        </span>
      )}
    </span>
  )
}
