import { CheckCircle2, CircleAlert, CircleHelp, Loader2, Server, XCircle } from 'lucide-react'
import { useApi } from '../api/ApiContext'
import { Notice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { config } from '../config'
import { useServiceHealth } from '../hooks/useServiceHealth'
import type { HealthResult } from '../types/api'
import { formatDateTimeWithZone } from '../utils/time'

const STATUS = {
  UP: { label: 'Healthy', icon: CheckCircle2 },
  DOWN: { label: 'Unhealthy', icon: XCircle },
  UNREACHABLE: { label: 'Unreachable', icon: CircleAlert },
} as const

function StatusPill({ result }: { result?: HealthResult }) {
  if (!result) {
    return (
      <span className="status-pill status-pill--pending">
        <Loader2 size={14} aria-hidden="true" className="spin" />
        Checking…
      </span>
    )
  }
  const { label, icon: Icon } = STATUS[result.status] ?? { label: result.status, icon: CircleHelp }
  return (
    <span className={`status-pill status-pill--${result.status.toLowerCase()}`}>
      <Icon size={14} aria-hidden="true" />
      {label}
    </span>
  )
}

export function ServiceHealthPage() {
  const api = useApi()
  const { results, isRefreshing, refresh } = useServiceHealth(api, config.refresh.health)
  const checked = api.services.filter((service) => results[service.id])
  const healthy = checked.filter((service) => results[service.id]?.status === 'UP').length

  return (
    <>
      <PageHeader
        title="Service Health"
        description="Health checks against the platform's backend services."
        onRefresh={() => void refresh()}
        refreshing={isRefreshing}
      />

      {api.mode === 'demo' ? (
        <Notice>
          Demo mode: these results are a snapshot recorded when the demo data was captured. No live health checks are
          made.
        </Notice>
      ) : (
        <Notice>
          Checks run in your browser every {config.refresh.health / 1000} seconds through the dashboard's API proxy.
          PostgreSQL and Kafka are monitored by Docker Compose health checks and are not exposed to the browser.
        </Notice>
      )}

      {checked.length === api.services.length && (
        <p className={`health-summary ${healthy === checked.length ? 'health-summary--ok' : 'health-summary--warn'}`} role="status">
          {healthy === checked.length ? (
            <CheckCircle2 size={18} aria-hidden="true" />
          ) : (
            <CircleAlert size={18} aria-hidden="true" />
          )}
          {healthy} of {checked.length} services healthy
        </p>
      )}

      <section className="card-grid" aria-label="Backend services">
        {api.services.map((service) => {
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
                  <dt>HTTP status</dt>
                  <dd>{result?.httpStatus ?? '—'}</dd>
                </div>
                <div>
                  <dt>Last checked</dt>
                  <dd>
                    {result ? (
                      <time dateTime={result.checkedAt.toISOString()} title={formatDateTimeWithZone(result.checkedAt)}>
                        {result.checkedAt.toLocaleTimeString()}
                      </time>
                    ) : (
                      '—'
                    )}
                  </dd>
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
