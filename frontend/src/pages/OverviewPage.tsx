import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router'
import { Notice } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'

const METRICS = [
  { label: 'Events ingested (24h)', availableIn: 'Phase 2' },
  { label: 'Open incidents', availableIn: 'Phase 3' },
  { label: 'Recurring failures', availableIn: 'Phase 3' },
  { label: 'Analyzed incidents', availableIn: 'Phase 4' },
]

const PIPELINE_STAGES = [
  { name: 'Log ingestion API', component: 'ingestion-service', state: 'Planned · Phase 2' },
  { name: 'Event streaming', component: 'Kafka (KRaft)', state: 'Broker provisioned' },
  { name: 'Incident correlation', component: 'incident-service', state: 'Planned · Phase 3' },
  { name: 'Root cause analysis', component: 'analysis-service', state: 'Planned · Phase 4' },
]

export function OverviewPage() {
  return (
    <>
      <PageHeader
        title="Operational Overview"
        description="Ingestion volume, open incidents and recurring failures across monitored applications."
        status="preview"
      />

      <Notice>
        This page is a starter layout. Metrics are not collected yet, so values appear as “—” until
        the phase that implements them is complete.
      </Notice>

      <section className="metric-grid" aria-label="Key metrics">
        {METRICS.map((metric) => (
          <article key={metric.label} className="card metric-card">
            <h2>{metric.label}</h2>
            <p className="metric-card__value" aria-label="No data yet">
              —
            </p>
            <p className="muted">Available in {metric.availableIn}</p>
          </article>
        ))}
      </section>

      <div className="two-column">
        <section className="card" aria-labelledby="pipeline-heading">
          <h2 id="pipeline-heading">Event pipeline</h2>
          <ol className="pipeline">
            {PIPELINE_STAGES.map((stage) => (
              <li key={stage.name}>
                <div>
                  <strong>{stage.name}</strong>
                  <span className="muted">{stage.component}</span>
                </div>
                <span className="tag">{stage.state}</span>
              </li>
            ))}
          </ol>
        </section>

        <section className="card" aria-labelledby="health-heading">
          <h2 id="health-heading">Service health</h2>
          <p>
            Health checks for the ingestion, incident and analysis services are already connected
            and run live from the dashboard.
          </p>
          <Link to="/health" className="text-link">
            View service health <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </section>
      </div>
    </>
  )
}
