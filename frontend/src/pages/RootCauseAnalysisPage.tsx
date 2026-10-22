import { Info, ListTree, Lock, Play, RefreshCw, SearchCode } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useApi } from '../api/ApiContext'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { canRunAnalysis } from '../auth/types'
import {
  AnalysisMeta,
  OtherCandidates,
  Patterns,
  PrimaryCandidate,
  RelatedIncidents,
  ReportLimitations,
} from '../components/AnalysisReport'
import { CategoryTag, ConfidenceBadge, IncidentStatusBadge, SeverityBadge } from '../components/Badges'
import { EmptyState, ErrorState, LoadingState } from '../components/DataState'
import { SearchFilter } from '../components/Filters'
import { PageHeader } from '../components/PageHeader'
import { Pagination } from '../components/Pagination'
import { Timestamp } from '../components/Timestamp'
import { useQuery } from '../hooks/useQuery'
import type { IncidentAnalysis } from '../types/api'
import { categoryLabel, formatCount } from '../utils/format'

const PICKER_PAGE_SIZE = 8

export function RootCauseAnalysisPage() {
  const { incidentId } = useParams()

  return (
    <>
      <PageHeader
        title="Root Cause Analysis"
        description="Rule-based analysis of an incident: potential causes with evidence, confidence and limitations."
      />
      <div className="rca-layout">
        <IncidentPicker selectedId={incidentId} />
        {incidentId ? (
          <AnalysisPanel key={incidentId} incidentId={incidentId} />
        ) : (
          <section className="card">
            <EmptyState icon={SearchCode} title="Select an incident">
              Choose an incident to see its stored analysis or to run a new one.
            </EmptyState>
          </section>
        )}
      </div>
    </>
  )
}

function IncidentPicker({ selectedId }: { selectedId?: string }) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const incidents = useQuery(`picker:${search}:${page}`, (signal) =>
    api.listIncidents({ q: search || undefined, page, size: PICKER_PAGE_SIZE }, signal),
  )
  const ids = incidents.data?.items.map((incident) => incident.id) ?? []
  const analyses = useQuery(`picker-analyses:${ids.join(',')}`, (signal) => api.latestAnalyses(ids, ids.length, signal), {
    enabled: ids.length > 0,
  })
  const analysisByIncident = new Map((analyses.data ?? []).map((analysis) => [analysis.incidentId, analysis]))

  return (
    <section className="card rca-picker" aria-labelledby="picker-heading">
      <h2 id="picker-heading">Incidents</h2>
      <SearchFilter
        id="picker-search"
        label="Search incidents"
        placeholder="Title…"
        value={search}
        onSearch={(value) => {
          setSearch(value)
          setPage(0)
        }}
      />
      {incidents.loading ? (
        <LoadingState />
      ) : !incidents.data ? (
        <ErrorState error={incidents.error} onRetry={incidents.reload} compact />
      ) : incidents.data.items.length === 0 ? (
        <EmptyState icon={ListTree} title={search ? 'No matching incidents' : 'No incidents yet'} />
      ) : (
        <>
          <ul className="picker-list">
            {incidents.data.items.map((incident) => {
              const analysis = analysisByIncident.get(incident.id)
              const selected = incident.id === selectedId
              return (
                <li key={incident.id}>
                  <Link
                    to={`/root-cause/${incident.id}`}
                    className={`picker-list__item ${selected ? 'picker-list__item--selected' : ''}`}
                    aria-current={selected ? 'page' : undefined}
                  >
                    <span className="picker-list__title">{incident.title}</span>
                    <span className="picker-list__meta">
                      <SeverityBadge severity={incident.severity} />
                      <span>{incident.application.service}</span>
                      <span>
                        {incident.application.name} · {incident.application.environment}
                      </span>
                      <span>{formatCount(incident.occurrenceCount)}×</span>
                    </span>
                    <span className="picker-list__meta">
                      {analysis ? (
                        <>
                          <ConfidenceBadge confidence={analysis.confidence} compact />
                          <span>{categoryLabel(analysis.primaryCategory)}</span>
                        </>
                      ) : (
                        <span className="muted">
                          {analyses.error !== undefined ? 'Analysis state unavailable' : 'Not analyzed'}
                        </span>
                      )}
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
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
  )
}

function AnalysisPanel({ incidentId }: { incidentId: string }) {
  const api = useApi()
  const incident = useQuery(`rca-incident:${incidentId}`, (signal) => api.getIncident(incidentId, signal))
  const stored = useQuery(`rca-analysis:${incidentId}`, (signal) => api.getLatestAnalysis(incidentId, signal))
  const history = useQuery(`rca-history:${incidentId}`, (signal) => api.analysisHistory(incidentId, signal))
  const [fresh, setFresh] = useState<IncidentAnalysis | null>(null)
  const [running, setRunning] = useState(false)
  const [runError, setRunError] = useState<unknown>(undefined)

  const analysis = fresh ?? stored.data ?? null
  // Also enforced by analysis-service, which answers 403 for other roles.
  const mayRun = canRunAnalysis(useAuth().session)
  const incidentMissing = incident.error instanceof ApiError && incident.error.isNotFound

  const run = async () => {
    setRunning(true)
    setRunError(undefined)
    try {
      setFresh(await api.analyzeIncident(incidentId))
      history.reload()
    } catch (error) {
      setRunError(error)
    } finally {
      setRunning(false)
    }
  }

  const runButton = !mayRun ? (
    <p className="inline-note" role="note">
      <Lock size={14} aria-hidden="true" /> Running an analysis requires the Administrator role.
    </p>
  ) : (
    <button
      type="button"
      className="button button--primary"
      onClick={() => void run()}
      disabled={running || incidentMissing}
    >
      {running ? <RefreshCw size={16} className="spin" aria-hidden="true" /> : <Play size={16} aria-hidden="true" />}
      {running ? 'Analyzing…' : analysis ? 'Re-run analysis' : 'Run analysis'}
    </button>
  )

  return (
    <div className="rca-panel">
      <section className="card" aria-labelledby="rca-incident-heading">
        {incident.data ? (
          <>
            <div className="card__header">
              <h2 id="rca-incident-heading">
                <Link to={`/incidents/${incident.data.id}`} className="table-link">
                  {incident.data.title}
                </Link>
              </h2>
              {runButton}
            </div>
            <div className="badge-row">
              <IncidentStatusBadge status={incident.data.status} />
              <SeverityBadge severity={incident.data.severity} />
              <span className="muted">
                {incident.data.application.service} · {incident.data.application.environment} ·{' '}
                {formatCount(incident.data.occurrenceCount)} occurrences · last{' '}
                <Timestamp value={incident.data.lastSeenAt} />
              </span>
            </div>
          </>
        ) : incident.loading ? (
          <LoadingState label="Loading incident…" />
        ) : incidentMissing ? (
          <EmptyState icon={SearchCode} title="Incident not found">
            No incident with ID <code>{incidentId}</code> exists.
          </EmptyState>
        ) : (
          <>
            <ErrorState error={incident.error} onRetry={incident.reload} compact />
            {analysis && <div className="card__header">{runButton}</div>}
          </>
        )}
        {api.mode === 'demo' && (
          <p className="inline-note">
            <Info size={14} aria-hidden="true" /> Demo mode: running an analysis shows the result stored in the demo
            data; the analysis engine does not run.
          </p>
        )}
        {runError !== undefined && <ErrorState error={runError} onRetry={() => void run()} compact />}
        {fresh && (
          <p className="inline-note inline-note--success" role="status">
            Analysis completed: revision {fresh.revision}.
          </p>
        )}
      </section>

      {stored.loading && !fresh ? (
        <LoadingState label="Loading analysis…" />
      ) : !analysis && stored.error !== undefined ? (
        <ErrorState error={stored.error} onRetry={stored.reload} />
      ) : !analysis ? (
        !incidentMissing && (
          <section className="card">
            <EmptyState icon={SearchCode} title="Not analyzed yet">
              No analysis is stored for this incident.{' '}
              {mayRun
                ? 'Run an analysis to see potential causes with their evidence.'
                : 'An administrator can run an analysis to show potential causes with their evidence.'}
            </EmptyState>
          </section>
        )
      ) : (
        <AnalysisView analysis={analysis} />
      )}

      {history.data && history.data.length > 1 && (
        <section className="card" aria-labelledby="history-heading">
          <h2 id="history-heading">Analysis history</h2>
          <ul className="item-list">
            {history.data.map((item) => (
              <li key={item.id}>
                <span className="item-list__title">
                  Revision {item.revision}: {item.primaryTitle ?? 'no candidate'}
                </span>
                <div className="item-list__meta">
                  <ConfidenceBadge confidence={item.confidence} compact />
                  <CategoryTag category={item.primaryCategory} />
                  <span>
                    <Timestamp value={item.generatedAt} /> · rules v{item.analysisVersion}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

function AnalysisView({ analysis }: { analysis: IncidentAnalysis }) {
  const [primary, ...others] = analysis.candidates
  return (
    <>
      <section className="card analysis-summary" aria-labelledby="analysis-summary-heading">
        <h2 id="analysis-summary-heading">Analysis summary</h2>
        <p>{analysis.summary}</p>
        <AnalysisMeta analysis={analysis} />
        <p className="disclaimer">
          <Info size={14} aria-hidden="true" />
          Suggestions come from documented rules applied to the error events. They are hypotheses supported by the
          evidence shown, not confirmed root causes.
        </p>
      </section>
      {primary && <PrimaryCandidate candidate={primary} />}
      <OtherCandidates candidates={others} />
      <RelatedIncidents items={analysis.relatedIncidents} compared={analysis.dataCoverage.incidentsCompared} />
      <Patterns analysis={analysis} />
      <ReportLimitations analysis={analysis} />
    </>
  )
}
