import { ChevronDown, ChevronRight, Inbox } from 'lucide-react'
import { Fragment, useState } from 'react'
import { useApi } from '../api/ApiContext'
import { SeverityBadge } from '../components/Badges'
import { EmptyState, ErrorState, LoadingState, StaleDataWarning } from '../components/DataState'
import { EventDetails } from '../components/EventDetails'
import { ClearFilters, SearchFilter, SelectFilter } from '../components/Filters'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { Timestamp } from '../components/Timestamp'
import { config } from '../config'
import { useQuery } from '../hooks/useQuery'
import { useUrlFilters } from '../hooks/useUrlFilters'
import { SEVERITIES, type Severity } from '../types/api'
import { SEVERITY_LABELS, shortFingerprint, simpleExceptionName } from '../utils/format'
import { distinctOptions } from '../utils/options'

const PAGE_SIZE = 25
const FILTERS = ['q', 'application', 'service', 'environment', 'severity'] as const

export function ErrorMonitoringPage() {
  const api = useApi()
  const { filters, page, setFilter, setPage, clear, active } = useUrlFilters(FILTERS)
  const [expanded, setExpanded] = useState<string | null>(null)

  const query = {
    q: filters.q || undefined,
    application: filters.application || undefined,
    service: filters.service || undefined,
    environment: filters.environment || undefined,
    severity: (filters.severity || undefined) as Severity | undefined,
    page,
    size: PAGE_SIZE,
  }
  const events = useQuery(`events:${JSON.stringify(query)}`, (signal) => api.listEvents(query, signal), {
    refreshMs: config.refresh.lists,
  })
  const applications = useQuery('applications', (signal) => api.listApplications(signal))
  const apps = applications.data ?? []

  return (
    <>
      <PageHeader
        title="Error Monitoring"
        description="Log and error events as reported by monitored applications, newest first."
        onRefresh={events.reload}
        refreshing={events.refreshing}
        updatedAt={events.updatedAt}
      />

      <section className="card" aria-labelledby="events-heading">
        <h2 id="events-heading" className="visually-hidden">
          Events
        </h2>
        <div className="filters" aria-label="Event filters">
          <SearchFilter
            id="event-search"
            label="Search"
            placeholder="Message or exception type…"
            value={filters.q}
            onSearch={(value) => setFilter('q', value)}
          />
          <SelectFilter
            id="event-application"
            label="Application"
            value={filters.application}
            allLabel="All applications"
            options={distinctOptions(apps.map((app) => app.name))}
            onChange={(value) => setFilter('application', value)}
          />
          <SelectFilter
            id="event-service"
            label="Service"
            value={filters.service}
            allLabel="All services"
            options={distinctOptions(apps.map((app) => app.service))}
            onChange={(value) => setFilter('service', value)}
          />
          <SelectFilter
            id="event-environment"
            label="Environment"
            value={filters.environment}
            allLabel="All environments"
            options={distinctOptions(apps.map((app) => app.environment))}
            onChange={(value) => setFilter('environment', value)}
          />
          <SelectFilter
            id="event-severity"
            label="Severity"
            value={filters.severity}
            allLabel="All severities"
            options={[...SEVERITIES].reverse().map((s) => ({ value: s, label: SEVERITY_LABELS[s] }))}
            onChange={(value) => setFilter('severity', value)}
          />
          {active && <ClearFilters onClear={clear} />}
        </div>

        {events.data && events.error !== undefined && <StaleDataWarning error={events.error} onRetry={events.reload} />}

        {events.loading ? (
          <LoadingState label="Loading events…" />
        ) : !events.data ? (
          <ErrorState error={events.error} onRetry={events.reload} />
        ) : events.data.items.length === 0 ? (
          <EmptyState icon={Inbox} title={active ? 'No events match these filters' : 'No events yet'}>
            {active
              ? 'Change or clear the filters to see more events.'
              : 'Events appear here once applications send them to POST /api/v1/logs.'}
          </EmptyState>
        ) : (
          <>
            <div className="table-wrapper" tabIndex={0} aria-label="Events table, scrolls horizontally">
              <table className="data-table">
                <thead>
                  <tr>
                    <th scope="col">
                      <span className="visually-hidden">Details</span>
                    </th>
                    <th scope="col">Occurred</th>
                    <th scope="col">Severity</th>
                    <th scope="col">Service</th>
                    <th scope="col">Error</th>
                    <th scope="col">Fingerprint</th>
                    <th scope="col">Trace / correlation</th>
                  </tr>
                </thead>
                <tbody>
                  {events.data.items.map((event) => {
                    const open = expanded === event.id
                    return (
                      <Fragment key={event.id}>
                        <tr className={open ? 'is-expanded' : undefined}>
                          <td>
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
                          <td className="nowrap">
                            <Timestamp value={event.timestamp} />
                          </td>
                          <td>
                            <SeverityBadge severity={event.severity} />
                          </td>
                          <td>
                            <div className="cell-stack">
                              <span>{event.application.service}</span>
                              <span className="muted">
                                {event.application.name} · {event.application.environment}
                              </span>
                            </div>
                          </td>
                          <td className="cell-message">
                            <div className="cell-stack">
                              {event.exceptionType && (
                                <code title={event.exceptionType}>{simpleExceptionName(event.exceptionType)}</code>
                              )}
                              <span>{event.message}</span>
                            </div>
                          </td>
                          <td>
                            <code title={event.fingerprint}>{shortFingerprint(event.fingerprint)}</code>
                          </td>
                          <td>
                            <div className="cell-stack">
                              {event.traceId && <code title="Trace ID">{event.traceId}</code>}
                              {event.correlationId && <code title="Correlation ID">{event.correlationId}</code>}
                              {!event.traceId && !event.correlationId && <span className="muted">—</span>}
                            </div>
                          </td>
                        </tr>
                        {open && (
                          <tr className="detail-row">
                            <td colSpan={7}>
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
            <Pagination
              page={events.data.page}
              totalPages={events.data.totalPages}
              totalItems={events.data.totalItems}
              itemLabel="events"
              onPageChange={setPage}
            />
          </>
        )}
      </section>
    </>
  )
}
