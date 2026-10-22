import { ListChecks } from 'lucide-react'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'

const ANALYSIS_OUTPUTS = [
  {
    title: 'Recurring patterns',
    description:
      'Occurrences over time, repeated exceptions and code locations, and errors that preceded the incident in the same trace.',
  },
  {
    title: 'Related incidents',
    description:
      'Incidents with the same error, exception, code location or message, ranked by a documented similarity score.',
  },
  {
    title: 'Potential root causes',
    description:
      'Ranked candidates with a LOW, MEDIUM or HIGH confidence level, the supporting evidence and known limitations.',
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
          <EmptyState icon={ListChecks} title="Not connected to the incident data yet">
            Incidents are created automatically from error events, but this page is not connected to
            the incident API yet (planned for Phase 5).
          </EmptyState>
        </section>

        <section className="card" aria-labelledby="analysis-heading">
          <h2 id="analysis-heading">Analysis output</h2>
          <p className="muted">
            The analysis service provides rule-based incident analysis through its API. This page is
            not connected to it yet (planned for Phase 5).
          </p>
          <ul className="feature-list">
            {ANALYSIS_OUTPUTS.map((output) => (
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
