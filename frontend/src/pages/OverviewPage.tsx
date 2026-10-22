import { ArrowRight, Inbox, SearchCode } from 'lucide-react'
import { Link } from 'react-router'
import { useApi } from '../api/ApiContext'
import { ConfidenceBadge, SeverityBadge } from '../components/Badges'
import { BarList, DailyColumns } from '../components/charts'
import { EmptyState, ErrorState, LoadingState, StaleDataWarning } from '../components/DataState'
import { IncidentTitle } from '../components/IncidentTitle'
import { PageHeader } from '../components/PageHeader'
import { StatCard } from '../components/StatCard'
import { Timestamp } from '../components/Timestamp'
import { config } from '../config'
import { useQuery } from '../hooks/useQuery'
import { categoryLabel, formatCount } from '../utils/format'

const TREND_DAYS = 14
const ACTIVITY_HOURS = 24

export function OverviewPage() {
  const api = useApi()
  const refreshMs = config.refresh.overview
  const stats = useQuery(`stats:${TREND_DAYS}`, (signal) => api.incidentStats(TREND_DAYS, signal), { refreshMs })
  const activity = useQuery(`activity:${ACTIVITY_HOURS}`, (signal) => api.eventActivity(ACTIVITY_HOURS, signal), {
    refreshMs,
  })
  const recent = useQuery('recent-incidents', (signal) => api.listIncidents({ size: 5 }, signal), { refreshMs })
  const analyses = useQuery('latest-analyses', (signal) => api.latestAnalyses(undefined, 5, signal), { refreshMs })

  const reloadAll = () => {
    stats.reload()
    activity.reload()
    recent.reload()
    analyses.reload()
  }
  const refreshing = stats.refreshing || activity.refreshing || recent.refreshing || analyses.refreshing
  const s = stats.data
  const a = activity.data
  const statsFailed = !s && stats.error !== undefined
  const activityFailed = !a && activity.error !== undefined
  const errorsReceived = a ? a.bySeverity.ERROR + a.bySeverity.FATAL : undefined

  return (
    <>
      <PageHeader
        title="Operational Overview"
        description="What is failing right now, where, and what the analyses suggest."
        onRefresh={reloadAll}
        refreshing={refreshing}
        updatedAt={stats.updatedAt}
      />

      {stats.error !== undefined && s && <StaleDataWarning error={stats.error} onRetry={stats.reload} />}
      {statsFailed && <ErrorState error={stats.error} onRetry={stats.reload} compact />}
      {activityFailed && <ErrorState error={activity.error} onRetry={activity.reload} compact />}

      <section className="metric-grid" aria-label="Key figures">
        <StatCard
          label="Unresolved incidents"
          value={s && s.byStatus.OPEN + s.byStatus.INVESTIGATING}
          unavailable={statsFailed}
          description={s ? `${formatCount(s.byStatus.OPEN)} open · ${formatCount(s.byStatus.INVESTIGATING)} investigating` : 'Open or investigating'}
        />
        <StatCard
          label="Total incidents"
          value={s?.totalIncidents}
          unavailable={statsFailed}
          description={s ? `Including ${formatCount(s.byStatus.RESOLVED)} resolved` : 'All statuses'}
        />
        <StatCard
          label="Fatal incidents"
          value={s?.unresolvedBySeverity.FATAL}
          unavailable={statsFailed}
          description="Unresolved, fatal severity"
        />
        <StatCard
          label="Services affected"
          value={s?.servicesAffected}
          unavailable={statsFailed}
          description="With unresolved incidents"
        />
        <StatCard
          label="Errors received (24 h)"
          value={errorsReceived}
          unavailable={activityFailed}
          description={a ? `Of ${formatCount(a.totalEvents)} events received` : 'Error and fatal events'}
        />
      </section>

      {s && s.totalIncidents === 0 && (
        <section className="card">
          <EmptyState icon={Inbox} title="No incidents yet">
            Incidents appear here once error events are ingested and correlated. Send the demo events with{' '}
            <code>python sample-data/send_sample_logs.py</code>.
          </EmptyState>
        </section>
      )}

      <div className="two-column">
        <section className="card" aria-labelledby="trend-heading">
          <div className="card__header">
            <h2 id="trend-heading">New incidents per day</h2>
            <span className="muted">Last {TREND_DAYS} days (UTC)</span>
          </div>
          {s ? (
            s.newIncidentsPerDay.some((day) => day.count > 0) ? (
              <DailyColumns days={s.newIncidentsPerDay} label="New incidents per day" valueLabel="new incidents" />
            ) : (
              <p className="muted">No incidents first occurred in the last {TREND_DAYS} days.</p>
            )
          ) : statsFailed ? (
            <p className="muted">Unavailable while the incident service cannot be reached.</p>
          ) : (
            <LoadingState />
          )}
        </section>

        <section className="card" aria-labelledby="services-heading">
          <div className="card__header">
            <h2 id="services-heading">Unresolved incidents by service</h2>
                      </div>
          {s ? (
            s.topServices.length > 0 ? (
              <BarList
                label="Unresolved incidents by service"
                items={s.topServices.map((row) => ({
                  label: row.service,
                  value: row.unresolvedIncidents,
                  note: `${formatCount(row.occurrences)} occurrences`,
                  href: `/incidents?service=${encodeURIComponent(row.service)}`,
                }))}
              />
            ) : (
              <p className="muted">No unresolved incidents.</p>
            )
          ) : statsFailed ? (
            <p className="muted">Unavailable while the incident service cannot be reached.</p>
          ) : (
            <LoadingState />
          )}
        </section>
      </div>

      <div className="two-column">
        <section className="card" aria-labelledby="recent-heading">
          <div className="card__header">
            <h2 id="recent-heading">Recent incident activity</h2>
            <Link to="/incidents" className="text-link">
              All incidents <ArrowRight size={16} aria-hidden="true" />
            </Link>
          </div>
          {recent.data ? (
            recent.data.items.length > 0 ? (
              <ul className="activity-list">
                {recent.data.items.map((incident) => (
                  <li key={incident.id}>
                    <div className="activity-list__main">
                      <IncidentTitle title={incident.title} to={`/incidents/${incident.id}`} showType={false} />
                      <span className="activity-list__meta">
                        {incident.application.service} · {incident.application.environment} ·{' '}
                        {formatCount(incident.occurrenceCount)}× · <Timestamp value={incident.lastSeenAt} />
                      </span>
                    </div>
                    <SeverityBadge severity={incident.severity} />
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">No incidents yet.</p>
            )
          ) : recent.error !== undefined ? (
            <ErrorState error={recent.error} onRetry={recent.reload} compact />
          ) : (
            <LoadingState />
          )}
        </section>

        <section className="card" aria-labelledby="errors-by-service-heading">
          <div className="card__header">
            <h2 id="errors-by-service-heading">Errors received by service</h2>
            <span className="muted">Last {ACTIVITY_HOURS} hours</span>
          </div>
          {a ? (
            a.byService.some((row) => row.errors > 0) ? (
              <BarList
                label="Errors received by service in the last 24 hours"
                items={a.byService
                  .filter((row) => row.errors > 0)
                  .sort((x, y) => y.errors - x.errors || x.service.localeCompare(y.service))
                  .slice(0, 8)
                  .map((row) => ({
                    label: row.service,
                    value: row.errors,
                    note: `${formatCount(row.events)} events`,
                    href: `/errors?service=${encodeURIComponent(row.service)}`,
                  }))}
              />
            ) : (
              <p className="muted">No ERROR or FATAL events were received in the last {ACTIVITY_HOURS} hours.</p>
            )
          ) : activityFailed ? (
            <p className="muted">Unavailable while the ingestion service cannot be reached.</p>
          ) : (
            <LoadingState />
          )}
        </section>
      </div>

      <section className="card" aria-labelledby="analyses-heading">
        <div className="card__header">
          <h2 id="analyses-heading">Latest analyses</h2>
          <Link to="/root-cause" className="text-link">
            Root cause analysis <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </div>
        {analyses.data ? (
          analyses.data.length > 0 ? (
            <ul className="activity-list">
              {analyses.data.map((analysis) => (
                <li key={analysis.incidentId}>
                  <div className="activity-list__main">
                    <Link to={`/root-cause/${analysis.incidentId}`} className="activity-list__title">
                      {analysis.primaryTitle ?? 'Analysis without a candidate'}
                    </Link>
                    <span className="activity-list__meta">
                      {categoryLabel(analysis.primaryCategory)}
                      {analysis.service && ` · ${analysis.service} · ${analysis.environment}`} · analyzed{' '}
                      <Timestamp value={analysis.generatedAt} />
                    </span>
                  </div>
                  <ConfidenceBadge confidence={analysis.confidence} compact />
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState icon={SearchCode} title="No analyses yet">
              Open an incident in Root Cause Analysis and run an analysis.
            </EmptyState>
          )
        ) : analyses.error !== undefined ? (
          <ErrorState error={analyses.error} onRetry={analyses.reload} compact />
        ) : (
          <LoadingState />
        )}
      </section>
    </>
  )
}
