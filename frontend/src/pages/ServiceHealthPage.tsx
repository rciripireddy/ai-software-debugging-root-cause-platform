import { RefreshCw, Server } from 'lucide-react'
import { Notice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { HEALTH_REFRESH_INTERVAL_MS, useServiceHealth } from '../hooks/useServiceHealth'
import { MONITORED_SERVICES, type HealthResult } from '../services/serviceHealth'

const STATUS_LABELS = {
  UP: 'Healthy',
  DOWN: 'Unhealthy',
  UNREACHABLE: 'Unreachable',
} as const

function StatusPill({ result }: { result?: HealthResult }) {
  if (!result) {
    return <span className="status-pill status-pill--pending">Checking…</span>
  }
  return (
    <span className={`status-pill status-pill--${result.status.toLowerCase()}`}>
      {STATUS_LABELS[result.status]}
    </span>
  )
}

export function ServiceHealthPage() {
  const { results, isRefreshing, refresh } = useServiceHealth(MONITORED_SERVICES)

  return (
    <>
      <PageHeader
        title="Service Health"
        description="Live health checks against the platform's backend services."
        status="live"
        actions={
          <button type="button" className="button" onClick={() => void refresh()} disabled={isRefreshing}>
            <RefreshCw size={16} aria-hidden="true" className={isRefreshing ? 'spin' : undefined} />
            Refresh
          </button>
        }
      />

      <Notice>
        Checks run in your browser every {HEALTH_REFRESH_INTERVAL_MS / 1000} seconds through the
        dashboard's API proxy. PostgreSQL and Kafka are monitored by Docker Compose health checks
        and are not exposed to the browser.
      </Notice>

      <section className="card-grid" aria-label="Backend services">
        {MONITORED_SERVICES.map((service) => {
          const result = results[service.id]
          return (
            <article key={service.id} className="card service-card" aria-label={service.name}>
              <div className="service-card__header">
                <span className="service-card__icon" aria-hidden="true">
                  <Server size={18} />
                </span>
                <div>
                  <h2>{service.name}</h2>
                  <p className="muted">{service.runtime}</p>
                </div>
                <StatusPill result={result} />
              </div>
              <p>{service.description}</p>
              <dl className="detail-list">
                <div>
                  <dt>Endpoint</dt>
                  <dd>
                    <code>{service.healthPath}</code>
                  </dd>
                </div>
                <div>
                  <dt>Response time</dt>
                  <dd>{result?.latencyMs !== undefined ? `${result.latencyMs} ms` : '—'}</dd>
                </div>
                <div>
                  <dt>Last checked</dt>
                  <dd>{result ? result.checkedAt.toLocaleTimeString() : '—'}</dd>
                </div>
              </dl>
              {result?.error && <p className="service-card__error">{result.error}</p>}
            </article>
          )
        })}
      </section>
    </>
  )
}
