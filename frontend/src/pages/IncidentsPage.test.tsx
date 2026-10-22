import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi, unavailable } from '../test/fakeApi'
import { CHECKOUT_INCIDENT_ID, incidentSummary, page, PAYMENT_INCIDENT_ID } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

const twoIncidents = page([
  incidentSummary(),
  incidentSummary({
    id: CHECKOUT_INCIDENT_ID,
    title: 'com.shop.checkout.PaymentFailedException: Order could not be completed',
    status: 'INVESTIGATING',
    severity: 'FATAL',
    application: { id: 'x', name: 'demo-store', service: 'checkout-service', environment: 'staging' },
    occurrenceCount: 12,
  }),
])

describe('IncidentsPage', () => {
  it('lists incidents with status, severity, service, count, last activity and analysis state', async () => {
    renderRoute('/incidents', createFakeApi({ listIncidents: vi.fn(async () => twoIncidents) }))

    const rows = within(await screen.findByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(within(rows[1]).getByRole('link', { name: 'Payment authorization failed' })).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('SocketTimeoutException')
    expect(rows[1]).toHaveTextContent('Open')
    expect(rows[1]).toHaveTextContent('payment-service')
    // The fingerprint is on the incident page, not in the list.
    expect(rows[1]).not.toHaveTextContent('9910dd1f3cee')
    expect(await within(rows[1]).findByText('HIGH')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Timeout')).toBeInTheDocument()
    expect(rows[2]).toHaveTextContent('Investigating')
    expect(rows[2]).toHaveTextContent('Fatal')
    expect(rows[2]).toHaveTextContent('12')
    expect(within(rows[2]).getByText('Not analyzed')).toBeInTheDocument()
  })

  it('requests the analysis state of the listed incidents in one call', async () => {
    const { api } = renderRoute('/incidents', createFakeApi({ listIncidents: vi.fn(async () => twoIncidents) }))
    await screen.findByRole('table')

    await vi.waitFor(() =>
      expect(api.latestAnalyses).toHaveBeenCalledWith([PAYMENT_INCIDENT_ID, CHECKOUT_INCIDENT_ID], 2, expect.anything()),
    )
  })

  it('filters by status, severity and service', async () => {
    const user = userEvent.setup()
    const { api } = renderRoute('/incidents')
    await screen.findByRole('table')

    await user.selectOptions(screen.getByLabelText('Status'), 'OPEN')
    await user.selectOptions(screen.getByLabelText('Severity'), 'ERROR')
    await user.selectOptions(await screen.findByLabelText('Service'), 'payment-service')
    await user.type(screen.getByLabelText('Search'), 'authorization{Enter}')

    expect(api.listIncidents).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'OPEN', severity: 'ERROR', service: 'payment-service', q: 'authorization', page: 0 }),
      expect.anything(),
    )
  })

  it('paginates', async () => {
    const user = userEvent.setup()
    const api = createFakeApi({
      listIncidents: vi.fn(async (query: { page?: number }) => page([incidentSummary()], query.page ?? 0, 20, 45)),
    })
    renderRoute('/incidents', api)

    expect(await screen.findByRole('navigation', { name: 'incidents pages' })).toHaveTextContent('45 incidents · page 1 of 3')
    await user.click(screen.getByRole('button', { name: /Next/ }))

    expect(api.listIncidents).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }), expect.anything())
    expect(await screen.findByRole('navigation', { name: 'incidents pages' })).toHaveTextContent('page 2 of 3')
    expect(screen.getByRole('button', { name: /Previous/ })).toBeEnabled()
  })

  it('opens the incident detail page', async () => {
    const user = userEvent.setup()
    const { router } = renderRoute('/incidents')

    await user.click(await screen.findByRole('link', { name: /Payment authorization failed/ }))

    expect(router.state.location.pathname).toBe(`/incidents/${PAYMENT_INCIDENT_ID}`)
    expect(await screen.findByRole('heading', { level: 2, name: 'Summary' })).toBeInTheDocument()
  })

  it('still lists incidents when the analysis service is down', async () => {
    renderRoute('/incidents', createFakeApi({ latestAnalyses: vi.fn().mockRejectedValue(unavailable('analysis-service')) }))

    expect(await screen.findByText(/Analysis state is unavailable/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Payment authorization failed/ })).toBeInTheDocument()
  })

  it('shows a clear error when the incident service is down', async () => {
    renderRoute('/incidents', createFakeApi({ listIncidents: vi.fn().mockRejectedValue(unavailable('incident-service')) }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Incident service unavailable')
  })
})
