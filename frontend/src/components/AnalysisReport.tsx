import {
  Activity,
  AlertTriangle,
  Boxes,
  Code2,
  FileCode,
  GitBranch,
  Info,
  Link2,
  MessageSquareText,
  Server,
  Timer,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import type { Candidate, Evidence, IncidentAnalysis, LinkedIncident, RelatedIncident } from '../types/api'
import { candidateLead, formatCount } from '../utils/format'
import { formatDuration } from '../utils/time'
import { CategoryTag, ConfidenceBadge } from './Badges'
import { CollapsibleCard, Disclosure } from './Disclosure'
import { Field } from './EventDetails'
import { IncidentTitle } from './IncidentTitle'
import { Timestamp } from './Timestamp'

/** Evidence items shown before "more evidence"; the rules list the strongest signals first. */
const KEY_EVIDENCE = 3

const SIGNAL_INFO: Record<string, { label: string; icon: LucideIcon }> = {
  EXCEPTION_TYPE: { label: 'Exception type', icon: AlertTriangle },
  MESSAGE: { label: 'Message pattern', icon: MessageSquareText },
  COMPONENT: { label: 'Code component', icon: Code2 },
  CODE_LOCATION: { label: 'Application frame', icon: FileCode },
  RECURRENCE: { label: 'Recurrence', icon: Timer },
  SERVICE: { label: 'Service', icon: Boxes },
  HOST: { label: 'Hosts', icon: Server },
  TRACE_CORRELATION: { label: 'Trace propagation', icon: GitBranch },
  UPSTREAM_CLASSIFICATION: { label: 'Upstream incident', icon: Link2 },
  WEAK_MATCH: { label: 'Weak match', icon: Activity },
}

function EvidenceList({ evidence }: { evidence: Evidence[] }) {
  return (
    <ul className="evidence-list">
      {evidence.map((item, index) => {
        const info = SIGNAL_INFO[item.signal] ?? { label: item.signal, icon: Activity }
        const Icon = info.icon
        return (
          <li key={`${item.signal}-${index}`}>
            <Icon size={18} aria-hidden="true" />
            <div>
              <span className="evidence-list__signal">{info.label}</span>
              <span>{item.description}</span>
            </div>
          </li>
        )
      })}
    </ul>
  )
}

/** The strongest evidence, with the rest one click away. */
function KeyEvidence({ evidence }: { evidence: Evidence[] }) {
  const rest = evidence.slice(KEY_EVIDENCE)
  return (
    <>
      <EvidenceList evidence={evidence.slice(0, KEY_EVIDENCE)} />
      {rest.length > 0 && (
        <Disclosure label={`${rest.length} more evidence signal${rest.length === 1 ? '' : 's'}`}>
          <EvidenceList evidence={rest} />
        </Disclosure>
      )}
    </>
  )
}

function Limitations({ items, title = 'Limitations' }: { items: string[]; title?: string }) {
  if (items.length === 0) return null
  return (
    <div className="limitations">
      <h3>{title}</h3>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function ConfidenceDetails({ candidate }: { candidate: Candidate }) {
  return (
    <details className="disclosure confidence-details">
      <summary>
        How this confidence was calculated ({candidate.score} of {candidate.maxScore} points)
      </summary>
      <div className="disclosure__body">
        <ul>
          {candidate.confidenceBasis.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        <p className="muted small">
          Points come from documented rules (5–6 = HIGH, 3–4 = MEDIUM, 0–2 = LOW) and are capped when evidence is
          incomplete. They are not probabilities.
        </p>
      </div>
    </details>
  )
}

/** The leading candidate: cause, confidence, explanation, key evidence and limitations first. */
export function PrimaryCandidate({ candidate, analysis }: { candidate: Candidate; analysis: IncidentAnalysis }) {
  const component = candidate.affectedComponent
  const coverage = analysis.dataCoverage
  return (
    <article className="card candidate candidate--primary" aria-labelledby="primary-candidate-title">
      <div className="candidate__heading">
        <p className="candidate__lead">{candidateLead(candidate.confidence)}</p>
        <h2 id="primary-candidate-title" className="candidate__title">
          {candidate.title}
        </h2>
        <div className="badge-row">
          <ConfidenceBadge confidence={candidate.confidence} />
          <CategoryTag category={candidate.category} />
        </div>
      </div>
      <p className="candidate__explanation">{candidate.explanation}</p>
      <div className="candidate__section">
        <h3>Key evidence</h3>
        <KeyEvidence evidence={candidate.evidence} />
      </div>
      <Limitations items={candidate.limitations} />
      <div className="candidate__more">
        <ConfidenceDetails candidate={candidate} />
        <Disclosure label="Technical details">
          <dl className="field-grid">
            <Field label="Affected service">
              {component.service} · {component.environment}
            </Field>
            <Field label="Code location">{component.location ? <code>{component.location}</code> : '—'}</Field>
            {component.source && (
              <Field label="Source">
                <code>{component.source}</code>
              </Field>
            )}
            {candidate.relatedIncidentIds.length > 0 && (
              <Field label="Related incident IDs">
                {candidate.relatedIncidentIds.map((id) => (
                  <Link key={id} to={`/root-cause/${id}`} className="text-link related-id">
                    <code>{id.slice(0, 8)}</code>
                  </Link>
                ))}
              </Field>
            )}
          </dl>
        </Disclosure>
      </div>
      <footer className="candidate__footer">
        <p className="disclaimer">
          <Info size={14} aria-hidden="true" />
          Suggestions come from documented rules applied to the error events. They are hypotheses supported by the
          evidence shown, not confirmed root causes.
        </p>
        <p className="muted small">
          Based on {formatCount(coverage.eventsAnalyzed)} of {formatCount(coverage.occurrenceCount)} events · analyzed{' '}
          <Timestamp value={analysis.generatedAt} />
        </p>
      </footer>
    </article>
  )
}

export function OtherCandidates({ candidates }: { candidates: Candidate[] }) {
  if (candidates.length === 0) return null
  return (
    <section className="card" aria-labelledby="other-candidates-heading">
      <div className="card__header">
        <h2 id="other-candidates-heading">Other candidates</h2>
        <span className="muted">Weaker explanations the evidence partly supports</span>
      </div>
      <div className="candidate-summaries">
        {candidates.map((candidate) => (
          <details key={candidate.rank} className="candidate-summary">
            <summary>
              <span className="candidate-summary__rank">#{candidate.rank}</span>
              <span className="candidate-summary__title">{candidate.title}</span>
              <ConfidenceBadge confidence={candidate.confidence} compact />
            </summary>
            <div className="candidate-summary__body">
              <CategoryTag category={candidate.category} />
              <p>{candidate.explanation}</p>
              <EvidenceList evidence={candidate.evidence} />
              <Limitations items={candidate.limitations} />
              <ConfidenceDetails candidate={candidate} />
            </div>
          </details>
        ))}
      </div>
    </section>
  )
}

function LinkedList({ title, items, direction }: { title: string; items: LinkedIncident[]; direction: 'before' | 'after' }) {
  if (items.length === 0) return null
  return (
    <div className="patterns__linked">
      <h3>{title}</h3>
      <ul className="item-list">
        {items.map((item) => (
          <li key={item.incidentId}>
            <IncidentTitle title={item.title} to={`/root-cause/${item.incidentId}`} showType={false} />
            <div className="item-list__meta">
              <span>{item.service}</span>
              <span>
                {item.basis === 'TRACE'
                  ? `${formatCount(item.sharedTraceEvents)} shared trace/correlation ID${item.sharedTraceEvents === 1 ? '' : 's'}`
                  : 'close in time only (no shared trace)'}
              </span>
              <span>
                median {item.medianGapSeconds} s {direction}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function Patterns({ analysis }: { analysis: IncidentAnalysis }) {
  const p = analysis.patterns
  const r = p.recurrence
  const linked = p.precedingErrors.length + p.subsequentErrors.length
  return (
    <CollapsibleCard
      id="patterns-heading"
      title="Patterns"
      hint={`${r.description.replace(/\.$/, '')}${linked > 0 ? ` · ${linked} linked error${linked === 1 ? '' : 's'} nearby` : ''}`}
    >
      <dl className="field-grid">
        <Field label="Recurrence">
          <span className="tag">{r.pattern}</span> {r.description}
        </Field>
        {r.medianIntervalSeconds !== null && (
          <Field label="Median interval">{formatDuration(r.medianIntervalSeconds)}</Field>
        )}
        {p.dominantException && (
          <Field label="Exception">
            <code>{p.dominantException.value}</code> ({p.dominantException.count}/{p.dominantException.total})
          </Field>
        )}
        {p.topApplicationFrame && (
          <Field label="Top application frame">
            <code>{p.topApplicationFrame.value}</code> ({p.topApplicationFrame.count}/{p.topApplicationFrame.total} with
            stack traces)
          </Field>
        )}
        <Field label="Hosts">
          {p.hosts.distinct === 0
            ? 'Not reported'
            : p.hosts.concentrated
              ? `All on ${p.hosts.top[0]?.value}`
              : p.hosts.top.map((host) => `${host.value} (${host.count})`).join(', ')}
        </Field>
      </dl>
      <LinkedList title="Errors that preceded this incident" items={p.precedingErrors} direction="before" />
      <LinkedList title="Errors that followed this incident" items={p.subsequentErrors} direction="after" />
    </CollapsibleCard>
  )
}

export function RelatedIncidents({ items, compared }: { items: RelatedIncident[]; compared: number }) {
  return (
    <section className="card" aria-labelledby="related-heading">
      <div className="card__header">
        <h2 id="related-heading">Related incidents</h2>
        <span className="muted">{formatCount(compared)} incidents compared</span>
      </div>
      {items.length === 0 ? (
        <p className="muted">
          No related incidents: no other incident of this application shares enough specific evidence (same error,
          exception, code location, message or trace).
        </p>
      ) : (
        <ul className="related-list">
          {items.map((item) => (
            <li key={item.incidentId}>
              <div className="related-list__header">
                <IncidentTitle title={item.title} to={`/root-cause/${item.incidentId}`} />
                <span className="related-list__score" title="Similarity score out of 100">
                  <span className="score-bar" aria-hidden="true">
                    <span style={{ width: `${item.score}%` }} />
                  </span>
                  {item.score}/100 · {item.strength === 'STRONG' ? 'Strong' : 'Partial'}
                </span>
              </div>
              <p className="muted small">
                {item.service} · {item.environment} · {item.status.toLowerCase()}
              </p>
              <Disclosure
                label={`Why it is related (${item.matchingSignals.length} matching signal${item.matchingSignals.length === 1 ? '' : 's'})`}
              >
                <ul className="signal-list" aria-label="Matching signals">
                  {item.matchingSignals.map((signal) => (
                    <li key={signal.signal}>
                      <span className="signal-list__points">+{signal.points}</span>
                      {signal.description}
                    </li>
                  ))}
                </ul>
              </Disclosure>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** Revision, rule version and data coverage: useful for auditing, secondary for reading. */
export function AnalysisDetails({ analysis }: { analysis: IncidentAnalysis }) {
  const c = analysis.dataCoverage
  return (
    <CollapsibleCard
      id="analysis-details-heading"
      title="Analysis details"
      hint={`Revision ${analysis.revision} · rules v${analysis.analysisVersion}`}
    >
      <p>{analysis.summary}</p>
      <dl className="field-grid">
        <Field label="Revision">{analysis.revision}</Field>
        <Field label="Rules version">v{analysis.analysisVersion}</Field>
        <Field label="Generated">
          <Timestamp value={analysis.generatedAt} format="exact" />
        </Field>
        <Field label="Events analyzed">
          {formatCount(c.eventsAnalyzed)} of {formatCount(c.occurrenceCount)}
        </Field>
        <Field label="With stack traces">{formatCount(c.eventsWithStackTrace)}</Field>
        <Field label="Incidents compared">{formatCount(c.incidentsCompared)}</Field>
      </dl>
    </CollapsibleCard>
  )
}

export function ReportLimitations({ analysis }: { analysis: IncidentAnalysis }) {
  if (analysis.limitations.length === 0) return null
  return (
    <section className="card" aria-labelledby="report-limitations-heading">
      <h2 id="report-limitations-heading">Limitations of this analysis</h2>
      <ul className="plain-list">
        {analysis.limitations.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  )
}
