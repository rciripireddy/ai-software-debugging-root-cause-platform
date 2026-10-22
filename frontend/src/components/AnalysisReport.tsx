import {
  Activity,
  AlertTriangle,
  Boxes,
  Code2,
  FileCode,
  GitBranch,
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
import { Field } from './EventDetails'
import { Timestamp } from './Timestamp'

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
            <Icon size={16} aria-hidden="true" />
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

function Limitations({ items, title = 'Limitations' }: { items: string[]; title?: string }) {
  if (items.length === 0) return null
  return (
    <div className="limitations">
      <h4>{title}</h4>
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
    <details className="confidence-details">
      <summary>
        How this confidence was calculated ({candidate.score} of {candidate.maxScore} points)
      </summary>
      <ul>
        {candidate.confidenceBasis.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      <p className="muted small">
        Points come from documented rules (5–6 = HIGH, 3–4 = MEDIUM, 0–2 = LOW) and are capped when evidence is
        incomplete. They are not probabilities.
      </p>
    </details>
  )
}

export function PrimaryCandidate({ candidate }: { candidate: Candidate }) {
  const component = candidate.affectedComponent
  return (
    <article className="card candidate candidate--primary" aria-labelledby="primary-candidate-title">
      <p className="candidate__lead">{candidateLead(candidate.confidence)}</p>
      <h2 id="primary-candidate-title" className="candidate__title">
        {candidate.title}
      </h2>
      <div className="badge-row">
        <ConfidenceBadge confidence={candidate.confidence} />
        <CategoryTag category={candidate.category} />
      </div>
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
      </dl>
      <p>{candidate.explanation}</p>
      <ConfidenceDetails candidate={candidate} />
      <h3>Evidence</h3>
      <EvidenceList evidence={candidate.evidence} />
      {candidate.relatedIncidentIds.length > 0 && (
        <p className="small">
          Related incident{candidate.relatedIncidentIds.length > 1 ? 's' : ''}:{' '}
          {candidate.relatedIncidentIds.map((id) => (
            <Link key={id} to={`/root-cause/${id}`} className="text-link">
              {id.slice(0, 8)}
            </Link>
          ))}
        </p>
      )}
      <Limitations items={candidate.limitations} />
    </article>
  )
}

export function OtherCandidates({ candidates }: { candidates: Candidate[] }) {
  if (candidates.length === 0) return null
  return (
    <section className="card" aria-labelledby="other-candidates-heading">
      <h2 id="other-candidates-heading">Other candidates</h2>
      <p className="muted small">Weaker explanations that the evidence also partly supports.</p>
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
            <ConfidenceDetails candidate={candidate} />
            <EvidenceList evidence={candidate.evidence} />
            <Limitations items={candidate.limitations} />
          </div>
        </details>
      ))}
    </section>
  )
}

function LinkedList({ title, items, direction }: { title: string; items: LinkedIncident[]; direction: 'before' | 'after' }) {
  if (items.length === 0) return null
  return (
    <div>
      <h3>{title}</h3>
      <ul className="item-list">
        {items.map((item) => (
          <li key={item.incidentId}>
            <Link to={`/root-cause/${item.incidentId}`} className="item-list__title">
              {item.title}
            </Link>
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
  return (
    <section className="card" aria-labelledby="patterns-heading">
      <h2 id="patterns-heading">Patterns</h2>
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
    </section>
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
                <Link to={`/root-cause/${item.incidentId}`} className="item-list__title">
                  {item.title}
                </Link>
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
              <ul className="signal-list" aria-label="Matching signals">
                {item.matchingSignals.map((signal) => (
                  <li key={signal.signal}>
                    <span className="signal-list__points">+{signal.points}</span>
                    {signal.description}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export function AnalysisMeta({ analysis }: { analysis: IncidentAnalysis }) {
  const c = analysis.dataCoverage
  return (
    <p className="muted small">
      Revision {analysis.revision} · rules v{analysis.analysisVersion} · generated{' '}
      <Timestamp value={analysis.generatedAt} /> · {formatCount(c.eventsAnalyzed)} of {formatCount(c.occurrenceCount)}{' '}
      events analyzed, {formatCount(c.eventsWithStackTrace)} with stack traces · {formatCount(c.incidentsCompared)}{' '}
      incidents compared
    </p>
  )
}

export function ReportLimitations({ analysis }: { analysis: IncidentAnalysis }) {
  return (
    <section className="card" aria-labelledby="report-limitations-heading">
      <h2 id="report-limitations-heading">Limitations of this analysis</h2>
      <Limitations items={analysis.limitations} title="What the analysis could not see" />
    </section>
  )
}
