import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi, unavailable } from '../test/fakeApi'
import { page, stats } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

describe('OverviewPage', () => {
  it('shows the counts returned by the APIs', async () => {
    renderRoute('/')

    const figures = screen.getByRole('region', { name: 'Key figures' })
    expect(await within(figures).findByRole('article', { name: 'Unresolved incidents' })).toHaveTextContent('16')
    expect(within(figures).getByRole('article', { name: 'Total incidents' })).toHaveTextContent('17')
    expect(within(figures).getByRole('article', { name: 'Total incidents' })).toHaveTextContent('Including 1 resolved')
    expect(within(figures).getByRole('article', { name: 'Fatal incidents' })).toHaveTextContent('2')
    expect(within(figures).getByRole('article', { name: 'Services affected' })).toHaveTextContent('13')
    expect(await within(figures).findByRole('article', { name: 'Errors received (24 h)' })).toHaveTextContent('48')
  })

  it('draws the charts from the returned data, with values as text', async () => {
    renderRoute('/')

    const byService = await screen.findByRole('list', { name: 'Unresolved incidents by service' })
    expect(within(byService).getByText('payment-service')).toBeInTheDocument()
    expect(within(byService).getByText('8 occurrences', { exact: false })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /New incidents per day\. Peak 5 on Oct 21/ })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: /Errors received by service/ })).toHaveTextContent('payment-service')
  })

  it('lists recent incidents and the latest analyses with links', async () => {
    renderRoute('/')

    const recent = await screen.findByRole('link', { name: 'Payment authorization failed' })
    expect(recent).toHaveAttribute('href', expect.stringMatching(/^\/incidents\//))
    const analysisLink = await screen.findByRole('link', { name: /Timeout waiting for a downstream dependency/ })
    expect(analysisLink).toHaveAttribute('href', expect.stringMatching(/^\/root-cause\//))
  })

  it('shows an empty state instead of invented numbers when there are no incidents', async () => {
    const api = createFakeApi({
      incidentStats: vi.fn(async () =>
        stats({
          totalIncidents: 0,
          byStatus: { OPEN: 0, INVESTIGATING: 0, RESOLVED: 0 },
          unresolvedBySeverity: { DEBUG: 0, INFO: 0, WARN: 0, ERROR: 0, FATAL: 0 },
          servicesAffected: 0,
          topServices: [],
          newIncidentsPerDay: [{ date: '2026-10-22', count: 0 }],
        }),
      ),
      listIncidents: vi.fn(async () => page([])),
      latestAnalyses: vi.fn(async () => []),
    })

    renderRoute('/', api)

    expect(await screen.findByRole('heading', { name: 'No incidents yet' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Total incidents' })).toHaveTextContent('0')
    expect(screen.getByText('No unresolved incidents.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'No analyses yet' })).toBeInTheDocument()
  })

  it('keeps working when one service is down', async () => {
    const api = createFakeApi({
      incidentStats: vi.fn(async () => {
        throw unavailable('incident-service')
      }),
      latestAnalyses: vi.fn(async () => {
        throw unavailable('analysis-service')
      }),
    })

    renderRoute('/', api)

    expect(await screen.findByText('Incident service unavailable')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Total incidents' })).toHaveTextContent('Unavailable')
    // Figures from the ingestion service are still shown.
    expect(await screen.findByRole('article', { name: 'Errors received (24 h)' })).toHaveTextContent('48')
    expect(await screen.findByText('Analysis service unavailable')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Retry' }).length).toBeGreaterThanOrEqual(2)
  })
})
