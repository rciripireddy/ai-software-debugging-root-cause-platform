import { ListChecks } from 'lucide-react'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'

const PLANNED_OUTPUTS = [
  {
    title: 'Correlated signals',
    description: 'Errors, services and deployments that occurred together within an incident window.',
  },
  {
    title: 'Recurring failure history',
    description: 'Previous incidents that share the same error fingerprint.',
  },
  {
    title: 'Candidate root causes',
    description: 'Ranked explanations, each with the supporting evidence used to produce it.',
  },
]

export function RootCauseAnalysisPage() {
  return (
    <>
      <PageHeader
        title="Root Cause Analysis"
        description="Investigate incidents and review likely causes with supporting evidence."
        status="preview"
      />

      <div className="two-column two-column--wide-right">
        <section className="card" aria-labelledby="incident-queue-heading">
          <h2 id="incident-queue-heading">Incident queue</h2>
          <EmptyState icon={ListChecks} title="No incidents to analyze">
            Incidents will appear here after incident correlation is implemented in Phase 3.
          </EmptyState>
        </section>

        <section className="card" aria-labelledby="analysis-heading">
          <h2 id="analysis-heading">Planned analysis output</h2>
          <p className="muted">
            Analysis is not implemented yet. The analysis service currently exposes only a health
            endpoint.
          </p>
          <ul className="feature-list">
            {PLANNED_OUTPUTS.map((output) => (
              <li key={output.title}>
                <strong>{output.title}</strong>
                <span>{output.description}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </>
  )
}
