import { useApi } from '../api/ApiContext'
import { useQuery } from '../hooks/useQuery'
import { ErrorState, LoadingState } from './DataState'
import { Disclosure } from './Disclosure'
import { StackTrace } from './StackTrace'
import { Timestamp } from './Timestamp'

/** Full details of one stored event (from ingestion-service), including its stack trace. */
export function EventDetails({ eventId }: { eventId: string }) {
  const api = useApi()
  const event = useQuery(`event:${eventId}`, (signal) => api.getEvent(eventId, signal))

  if (event.loading) return <LoadingState label="Loading event details…" />
  if (!event.data) return <ErrorState error={event.error} onRetry={event.reload} compact />

  const e = event.data
  return (
    <div className="event-details">
      <p className="event-details__message">{e.message}</p>
      <dl className="field-grid">
        <Field label="Occurred">
          <Timestamp value={e.timestamp} format="exact" />
        </Field>
        <Field label="Exception type">{e.exceptionType ? <code>{e.exceptionType}</code> : '—'}</Field>
        <Field label="Source">{e.source ? <code>{e.source}</code> : '—'}</Field>
        <Field label="Host">{e.host ?? '—'}</Field>
      </dl>
      {e.stackTrace ? <StackTrace trace={e.stackTrace} /> : <p className="muted">No stack trace was reported for this event.</p>}
      <Disclosure label="Technical details">
        <dl className="field-grid">
          <Field label="Application">
            {e.application.name} · {e.application.service} · {e.application.environment}
          </Field>
          <Field label="Received">
            <Timestamp value={e.receivedAt} format="exact" />
          </Field>
          <Field label="Trace ID">{e.traceId ? <code>{e.traceId}</code> : '—'}</Field>
          <Field label="Correlation ID">{e.correlationId ? <code>{e.correlationId}</code> : '—'}</Field>
          <Field label="Fingerprint">
            <code>{e.fingerprint}</code>
          </Field>
          <Field label="Event ID">
            <code>{e.id}</code>
          </Field>
        </dl>
      </Disclosure>
    </div>
  )
}

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
