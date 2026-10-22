import { ListTree } from 'lucide-react'
import { Link } from 'react-router'
import { useApi } from '../api/ApiContext'
import { ConfidenceBadge, IncidentStatusBadge, SeverityBadge } from '../components/Badges'
import { EmptyState, ErrorState, LoadingState, StaleDataWarning } from '../components/DataState'
import { IncidentTitle } from '../components/IncidentTitle'
import { ClearFilters, SearchFilter, SelectFilter } from '../components/Filters'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { Timestamp } from '../components/Timestamp'
import { config } from '../config'
import { useQuery } from '../hooks/useQuery'
import { useUrlFilters } from '../hooks/useUrlFilters'
import { INCIDENT_STATUSES, SEVERITIES, type IncidentStatus, type Severity } from '../types/api'
import { categoryLabel, formatCount, SEVERITY_LABELS, STATUS_LABELS } from '../utils/format'
import { distinctOptions } from '../utils/options'

const PAGE_SIZE = 20
const FILTERS = ['q', 'status', 'severity', 'application', 'service', 'environment'] as const

export function IncidentsPage() {
  const api = useApi()
  const { filters, page, setFilter, setPage, clear, active } = useUrlFilters(FILTERS)

  const query = {
    q: filters.q || undefined,
    status: (filters.status || undefined) as IncidentStatus | undefined,
    severity: (filters.severity || undefined) as Severity | undefined,
    application: filters.application || undefined,
    service: filters.service || undefined,
    environment: filters.environment || undefined,
    page,
    size: PAGE_SIZE,
  }
  const incidents = useQuery(`incidents:${JSON.stringify(query)}`, (signal) => api.listIncidents(query, signal), {
    refreshMs: config.refresh.lists,
  })
  const applications = useQuery('applications', (signal) => api.listApplications(signal))
  const ids = incidents.data?.items.map((incident) => incident.id) ?? []
  // Analysis state of the incidents on this page; optional, the list works without it.
  const analyses = useQuery(`analyses:${ids.join(',')}`, (signal) => api.latestAnalyses(ids, ids.length, signal), {
    enabled: ids.length > 0,
  })
  const analysisByIncident = new Map((analyses.data ?? []).map((analysis) => [analysis.incidentId, analysis]))
  const apps = applications.data ?? []

  return (
    <>
      <PageHeader
        title="Incidents"
        description="Recurring errors grouped automatically, most recent activity first."
        onRefresh={() => {
          incidents.reload()
          analyses.reload()
        }}
        refreshing={incidents.refreshing}
        updatedAt={incidents.updatedAt}
      />

      <section className="card" aria-labelledby="incidents-heading">
        <h2 id="incidents-heading" className="visually-hidden">
          Incident list
        </h2>
        <div className="filters" aria-label="Incident filters">
          <SearchFilter
            id="incident-search"
            label="Search"
            placeholder="Title…"
            value={filters.q}
            onSearch={(value) => setFilter('q', value)}
          />
          <SelectFilter
            id="incident-status"
            label="Status"
            value={filters.status}
            allLabel="All statuses"
            options={INCIDENT_STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))}
            onChange={(value) => setFilter('status', value)}
          />
          <SelectFilter
            id="incident-severity"
            label="Severity"
            value={filters.severity}
            allLabel="All severities"
            options={[...SEVERITIES].reverse().map((s) => ({ value: s, label: SEVERITY_LABELS[s] }))}
            onChange={(value) => setFilter('severity', value)}
          />
          <SelectFilter
            id="incident-application"
            label="Application"
            value={filters.application}
            allLabel="All applications"
            options={distinctOptions(apps.map((app) => app.name))}
            onChange={(value) => setFilter('application', value)}
          />
          <SelectFilter
            id="incident-service"
            label="Service"
            value={filters.service}
            allLabel="All services"
            options={distinctOptions(apps.map((app) => app.service))}
            onChange={(value) => setFilter('service', value)}
          />
          <SelectFilter
            id="incident-environment"
            label="Environment"
            value={filters.environment}
            allLabel="All environments"
            options={distinctOptions(apps.map((app) => app.environment))}
            onChange={(value) => setFilter('environment', value)}
          />
          {active && <ClearFilters onClear={clear} />}
        </div>

        {incidents.data && incidents.error !== undefined && (
          <StaleDataWarning error={incidents.error} onRetry={incidents.reload} />
        )}

        {incidents.loading ? (
          <LoadingState label="Loading incidents…" />
        ) : !incidents.data ? (
          <ErrorState error={incidents.error} onRetry={incidents.reload} />
        ) : incidents.data.items.length === 0 ? (
          <EmptyState icon={ListTree} title={active ? 'No incidents match these filters' : 'No incidents yet'}>
            {active
              ? 'Change or clear the filters to see more incidents.'
              : 'Incidents are created automatically when error events of ERROR or FATAL severity are ingested.'}
          </EmptyState>
        ) : (
          <>
            {analyses.error !== undefined && (
              <p className="inline-note" role="status">
                Analysis state is unavailable (analysis service not reachable); the incident list itself is complete.
              </p>
            )}
            <div className="table-wrapper" tabIndex={0} aria-label="Incidents table, scrolls horizontally">
              <table className="data-table data-table--cards">
                <thead>
                  <tr>
                    <th scope="col">Incident</th>
                    <th scope="col">Severity</th>
                    <th scope="col">Status</th>
                    <th scope="col">Service</th>
                    <th scope="col" className="numeric">
                      Occurrences
                    </th>
                    <th scope="col">Last seen</th>
                    <th scope="col">Analysis</th>
                  </tr>
                </thead>
                <tbody>
                  {incidents.data.items.map((incident) => {
                    const analysis = analysisByIncident.get(incident.id)
                    return (
                      <tr key={incident.id}>
                        <td className="cell-message cell-primary">
                          <IncidentTitle title={incident.title} to={`/incidents/${incident.id}`} />
                        </td>
                        <td className="cell-badge">
                          <SeverityBadge severity={incident.severity} />
                        </td>
                        <td className="cell-badge">
                          <IncidentStatusBadge status={incident.status} />
                        </td>
                        <td data-label="Service">
                          <div className="cell-stack">
                            <span>{incident.application.service}</span>
                            <span className="muted">
                              {incident.application.name} · {incident.application.environment}
                            </span>
                          </div>
                        </td>
                        <td className="numeric" data-label="Occurrences">
                          {formatCount(incident.occurrenceCount)}
                        </td>
                        <td className="nowrap" data-label="Last seen">
                          <Timestamp value={incident.lastSeenAt} />
                        </td>
                        <td data-label="Analysis">
                          {analysis ? (
                            <Link to={`/root-cause/${incident.id}`} className="cell-stack table-link">
                              <ConfidenceBadge confidence={analysis.confidence} compact />
                              <span className="muted">{categoryLabel(analysis.primaryCategory)}</span>
                            </Link>
                          ) : analyses.loading ? (
                            <span className="muted">…</span>
                          ) : analyses.error !== undefined ? (
                            <span className="muted">Unavailable</span>
                          ) : (
                            <Link to={`/root-cause/${incident.id}`} className="table-link muted">
                              Not analyzed
                            </Link>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pagination
              page={incidents.data.page}
              totalPages={incidents.data.totalPages}
              totalItems={incidents.data.totalItems}
              itemLabel="incidents"
              onPageChange={setPage}
            />
          </>
        )}
      </section>
    </>
  )
}
