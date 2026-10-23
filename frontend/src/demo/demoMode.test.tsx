import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderRoute } from '../test/renderRoute'
import { createDemoApi, type DemoDataset } from './demoApi'
import { createDemoAuthApi, DEMO_SESSION } from './demoAuth'
import { loadDemoData } from './loadDemoData'

let data: DemoDataset
const fetchMock = vi.fn(() => Promise.reject(new Error('demo mode must not make network requests')))

beforeEach(async () => {
  data = await loadDemoData()
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockClear()
})

describe('demo dataset', () => {
  it('is synthetic demo data with every part the dashboard needs', () => {
    expect(data.description).toMatch(/^Synthetic demo data/)
    expect(new Set(data.incidents.map((incident) => incident.application.service)).size).toBeGreaterThan(5)
    expect(new Set(data.events.map((event) => event.severity))).toEqual(new Set(['INFO', 'WARN', 'ERROR', 'FATAL']))
    expect(data.incidents.some((incident) => incident.occurrenceCount > 2)).toBe(true)
    const analyses = Object.values(data.analyses)
    expect(analyses.some((analysis) => analysis.confidence === 'HIGH')).toBe(true)
    expect(analyses.some((analysis) => analysis.primaryCandidate?.category === 'UNKNOWN')).toBe(true)
    expect(analyses.some((analysis) => analysis.relatedIncidents.length > 0)).toBe(true)
    expect(Object.values(data.health).every((result) => result.status === 'UP')).toBe(true)
  })

  it('contains no event metadata or raw payloads', () => {
    for (const event of data.events) {
      expect(event).not.toHaveProperty('metadata')
      expect(event).not.toHaveProperty('rawPayload')
    }
  })

  it('is a small curated set with every confidence level and no test or verification data', () => {
    expect(data.incidents.length).toBeGreaterThanOrEqual(8)
    expect(data.incidents.length).toBeLessThanOrEqual(15)
    const confidences = new Set(Object.values(data.analyses).map((analysis) => analysis.confidence))
    expect(confidences).toEqual(new Set(['HIGH', 'MEDIUM', 'LOW']))
    expect(new Set(Object.values(data.analyses).map((a) => a.primaryCandidate?.category)).size).toBeGreaterThanOrEqual(5)
    for (const name of new Set(data.applications.map((application) => application.name))) {
      expect(name).toMatch(/^demo-/)
    }
  })

  it('references only incidents and events that are part of the dataset', () => {
    const incidentIds = new Set(data.incidents.map((incident) => incident.id))
    const eventIds = new Set(data.events.map((event) => event.id))
    for (const incident of data.incidents) {
      expect(data.analyses[incident.id]?.incidentId).toBe(incident.id)
      for (const event of incident.recentEvents) expect(eventIds).toContain(event.id)
    }
    for (const id of [...Object.keys(data.analyses), ...Object.keys(data.history), ...Object.keys(data.related)]) {
      expect(incidentIds).toContain(id)
    }
    for (const related of [...Object.values(data.related).flat(), ...Object.values(data.analyses).flatMap((a) => a.relatedIncidents)]) {
      expect(incidentIds).toContain(related.incidentId)
    }
  })

  it('contains no local or internal addresses, credentials or e-mail addresses', () => {
    const text = JSON.stringify(data)
    expect(text).not.toMatch(/localhost|127\.0\.0\.1|host\.docker\.internal/i)
    expect(text).not.toMatch(/(ingestion-service|incident-service|analysis-service|postgres|kafka):\d+/i)
    expect(text).not.toMatch(/jdbc:|postgres(ql)?:\/\/|Bearer\s+ey|password\s*[=:]/i)
    // In JSON text a Windows path's backslash is escaped (C:\\Users).
    expect(text).not.toMatch(/[A-Za-z]:(\\{1,2}|\/)Users|\/home\/|\/Users\//)
    expect(text).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.]+/)
  })
})

describe('demo mode', () => {
  it('needs no sign-in: a synthetic demo session without credentials or requests', async () => {
    const { router } = renderRoute('/login', createDemoApi(data), { session: DEMO_SESSION, authApi: createDemoAuthApi() })

    expect(await screen.findByRole('heading', { level: 1, name: 'Operational Overview' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
    const card = screen.getByText('Demo visitor').closest('.user-card') as HTMLElement
    expect(within(card).getByText('Demo session')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument()
    expect(DEMO_SESSION.expiresAt).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('labels every page as demo data and makes no network requests', async () => {
    const user = userEvent.setup()
    renderRoute('/', createDemoApi(data))

    expect(screen.getByRole('note')).toHaveTextContent('Demo mode.')
    expect(screen.getByText('Demo data', { selector: '.env-pill' })).toBeInTheDocument()
    expect(screen.getByText('Demo data', { selector: '.feature-badge' })).toBeInTheDocument()
    expect(await screen.findByRole('article', { name: 'Total incidents' })).toHaveTextContent(String(data.incidents.length))

    for (const page of ['Error Monitoring', 'Incidents', 'Root Cause Analysis', 'Service Health']) {
      await user.click(within(screen.getByRole('navigation', { name: 'Primary' })).getByRole('link', { name: page }))
      expect(await screen.findByRole('heading', { level: 1, name: page })).toBeInTheDocument()
      expect(screen.getByText('Demo data', { selector: '.feature-badge' })).toBeInTheDocument()
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows the captured health snapshot of a demo environment, not live checks', async () => {
    renderRoute('/health', createDemoApi(data))

    expect(screen.getByText(/Demo environment: no backend services run behind this preview/)).toBeInTheDocument()
    expect(screen.getByText(/snapshot recorded when\s+the demo data was captured/)).toBeInTheDocument()
    expect(await screen.findByText('3 of 3 services healthy when the demo data was captured')).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('demo sign-in, session restore and sign-out make no requests', async () => {
    const auth = createDemoAuthApi()
    await expect(auth.restore()).resolves.toBe(DEMO_SESSION)
    await expect(auth.login('anyone', 'anything')).resolves.toBe(DEMO_SESSION)
    await expect(auth.logout(DEMO_SESSION)).resolves.toBeUndefined()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('filters demo events and shows stored analyses without running the engine', async () => {
    const api = createDemoApi(data)
    const fatal = await api.listEvents({ severity: 'FATAL' })
    expect(fatal.items.length).toBeGreaterThan(0)
    expect(fatal.items.every((event) => event.severity === 'FATAL')).toBe(true)

    const [incidentId, stored] = Object.entries(data.analyses).find(([, analysis]) => analysis.confidence === 'HIGH')!
    await expect(api.analyzeIncident(incidentId)).resolves.toBe(stored)

    const user = userEvent.setup()
    renderRoute(`/root-cause/${incidentId}`, api)
    expect(await screen.findByText(/running an analysis shows the result stored in the demo data/)).toBeInTheDocument()
    expect(await screen.findByText('Most likely cause based on available evidence')).toBeInTheDocument()

    // "Re-run" shows the stored result and says so; it does not report a completed analysis.
    await user.click(screen.getByRole('button', { name: 'Re-run analysis' }))
    expect(await screen.findByRole('status')).toHaveTextContent(
      `Showing the stored demo analysis (revision ${stored.revision}); no new analysis was run.`,
    )
    expect(screen.queryByText(/Analysis completed/)).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('computes overview statistics from the bundled incidents', async () => {
    const stats = await createDemoApi(data).incidentStats(14)

    expect(stats.totalIncidents).toBe(data.incidents.length)
    expect(stats.newIncidentsPerDay).toHaveLength(14)
    expect(stats.newIncidentsPerDay.at(-1)?.date).toBe(data.capturedAt.slice(0, 10))
  })
})
