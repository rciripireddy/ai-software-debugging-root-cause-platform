import { ArrowLeft, ChevronDown, ChevronRight, SearchCode, SearchX } from 'lucide-react'
import { Fragment, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useApi } from '../api/ApiContext'
import { ApiError } from '../api/http'
import { CategoryTag, ConfidenceBadge, IncidentStatusBadge, SeverityBadge } from '../components/Badges'
import { EmptyState, ErrorState, LoadingState, StaleDataWarning } from '../components/DataState'
import { Disclosure } from '../components/Disclosure'
import { EventDetails, Field } from '../components/EventDetails'
import { PageHeader } from '../components/PageHeader'
import { Timestamp } from '../components/Timestamp'
import { config } from '../config'
import { useQuery } from '../hooks/useQuery'
import { formatCount, simpleExceptionName, splitIncidentTitle } from '../utils/format'

export function IncidentDetailPage() {
  const { incidentId = '' } = useParams()
  const api = useApi()
  const incident = useQuery(`incident:${incidentId}`, (signal) => api.getIncident(incidentId, signal), {
    refreshMs: config.refresh.lists,
  })
  const analysis = useQuery(`analysis-summary:${incidentId}`, (signal) => api.latestAnalyses([incidentId], 1, signal))
  const [expanded, setExpanded] = useState<string | null>(null)

  const back = (
    <Link to="/incidents" className="text-link">
      <ArrowLeft size={16} aria-hidden="true" /> All incidents
    </Link>
  )

  if (incident.loading) return <LoadingState label="Loading incident…" />
  if (!incident.data) {
    if (incident.error instanceof ApiError && incident.error.isNotFound) {
      return (
        <section className="card">
          {back}
          <EmptyState icon={SearchX} title="Incident not found">
            No incident with ID <code>{incidentId}</code> exists.
          </EmptyState>
        </section>
      )
    }
    return (
      <>
        {back}
        <ErrorState error={incident.error} onRetry={incident.reload} />
      </>
    )
  }

  const i = incident.data
  const latestAnalysis = analysis.data?.[0]
  const newestEvent = i.recentEvents[0]
  const { headline, exceptionType } = splitIncidentTitle(i.title)
  const where = `${i.application.service} · ${i.application.environment} · ${i.application.name}`

  return (
    <>
      {back}
      <PageHeader
        title={headline}
        description={exceptionType ? `${simpleExceptionName(exceptionType)} in ${where}` : `Incident in ${where}`}
        onRefresh={incident.reload}
        refreshing={incident.refreshing}
        updatedAt={incident.updatedAt}
        actions={
          <Link to={`/root-cause/${i.id}`} className="button button--primary">
            <SearchCode size={16} aria-hidden="true" />
            Root cause analysis
          </Link>
        }
      />
      {incident.error !== undefined && <StaleDataWarning error={incident.error} onRetry={incident.reload} />}

      <div className="two-column two-column--wide-left">
        <section className="card" aria-labelledby="summary-heading">
          <div className="card__header">
            <h2 id="summary-heading">Summary</h2>
            <div className="badge-row">
              <IncidentStatusBadge status={i.status} />
              <SeverityBadge severity={i.severity} />
            </div>
          </div>
          <dl className="key-facts">
            <Field label="Occurrences">{formatCount(i.occurrenceCount)}</Field>
            <Field label="Service">{i.application.service}</Field>
            <Field label="Environment">{i.application.environment}</Field>
            <Field label="Last seen">
              <Timestamp value={i.lastSeenAt} />
            </Field>
          </dl>
          {i.summary && <p>{i.summary}</p>}
          <Disclosure label="Technical details">
            <dl className="field-grid">
              <Field label="Application">{i.application.name}</Field>
              <Field label="First seen">
                <Timestamp value={i.firstSeenAt} format="exact" />
              </Field>
              <Field label="Last seen">
                <Timestamp value={i.lastSeenAt} format="exact" />
              </Field>
              {exceptionType && (
                <Field label="Exception type">
                  <code>{exceptionType}</code>
                </Field>
              )}
              <Field label="Fingerprint">
                <code className="break-all">{i.fingerprint}</code>
              </Field>
              <Field label="Incident ID">
                <code className="break-all">{i.id}</code>
              </Field>
            </dl>
          </Disclosure>
        </section>

        <section className="card" aria-labelledby="analysis-state-heading">
          <h2 id="analysis-state-heading">Likely root cause</h2>
          {latestAnalysis ? (
            <>
              <p className="root-cause-card__title">{latestAnalysis.primaryTitle ?? 'No candidate identified'}</p>
              <div className="badge-row">
                <ConfidenceBadge confidence={latestAnalysis.confidence} />
                <CategoryTag category={latestAnalysis.primaryCategory} />
              </div>
              <p className="muted small">
                Analyzed <Timestamp value={latestAnalysis.generatedAt} />
              </p>
              <Link to={`/root-cause/${i.id}`} className="text-link">
                View evidence and related incidents
              </Link>
            </>
          ) : analysis.loading ? (
            <LoadingState />
          ) : analysis.error !== undefined ? (
            <ErrorState error={analysis.error} onRetry={analysis.reload} compact />
          ) : (
            <>
              <p className="muted">This incident has not been analyzed yet.</p>
              <Link to={`/root-cause/${i.id}`} className="text-link">
                Run an analysis
              </Link>
            </>
          )}
        </section>
      </div>

      {newestEvent && (
        <section className="card" aria-labelledby="latest-heading">
          <div className="card__header">
            <h2 id="latest-heading">Most recent occurrence</h2>
            <Timestamp value={newestEvent.timestamp} />
          </div>
          <EventDetails eventId={newestEvent.id} />
        </section>
      )}

      <section className="card" aria-labelledby="events-heading">
        <div className="card__header">
          <h2 id="events-heading">Error events</h2>
          <span className="muted">
            {i.recentEvents.length < i.occurrenceCount
              ? `${formatCount(i.recentEvents.length)} most recent of ${formatCount(i.occurrenceCount)}`
              : `${formatCount(i.recentEvents.length)} events`}
          </span>
        </div>
        <div className="table-wrapper" tabIndex={0} aria-label="Incident events table, scrolls horizontally">
          <table className="data-table data-table--cards">
            <thead>
              <tr>
                <th scope="col">
                  <span className="visually-hidden">Details</span>
                </th>
                <th scope="col">Occurred</th>
                <th scope="col">Severity</th>
                <th scope="col">Error</th>
                <th scope="col">Host</th>
              </tr>
            </thead>
            <tbody>
              {i.recentEvents.map((event) => {
                const open = expanded === event.id
                return (
                  <Fragment key={event.id}>
                    <tr className={open ? 'is-expanded' : undefined}>
                      <td className="cell-toggle">
                        <button
                          type="button"
                          className="icon-button icon-button--small"
                          aria-expanded={open}
                          aria-label={open ? 'Hide event details' : 'Show event details'}
                          onClick={() => setExpanded(open ? null : event.id)}
                        >
                          {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                        </button>
                      </td>
                      <td className="nowrap" data-label="Occurred">
                        <Timestamp value={event.timestamp} format="exact" />
                      </td>
                      <td className="cell-badge">
                        <SeverityBadge severity={event.severity} />
                      </td>
                      <td className="cell-message cell-primary">
                        <div className="cell-stack">
                          <span>{event.message}</span>
                          {event.exceptionType && (
                            <code className="cell-type" title={event.exceptionType}>
                              {simpleExceptionName(event.exceptionType)}
                            </code>
                          )}
                        </div>
                      </td>
                      <td data-label="Host">{event.host ?? <span className="muted">—</span>}</td>
                    </tr>
                    {open && (
                      <tr className="detail-row">
                        <td colSpan={5}>
                          <EventDetails eventId={event.id} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
        <p className="muted small">Expand an event to see its stack trace, trace ID and correlation ID.</p>
      </section>
    </>
  )
}
