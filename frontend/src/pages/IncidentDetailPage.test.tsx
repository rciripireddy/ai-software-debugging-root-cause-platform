import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi, notFound, unavailable } from '../test/fakeApi'
import { eventDetail, PAYMENT_INCIDENT_ID } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

const path = `/incidents/${PAYMENT_INCIDENT_ID}`

describe('IncidentDetailPage', () => {
  it('shows the incident summary', async () => {
    renderRoute(path)

    const summary = (await screen.findByRole('heading', { name: 'Summary' })).closest('section')!
    expect(summary).toHaveTextContent('Occurrences4')
    expect(summary).toHaveTextContent('Servicepayment-service')
    expect(summary).toHaveTextContent('Environmentstaging')
    expect(within(summary).getByText('Open')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/^Payment authorization failed/)
    expect(screen.getByText(/^SocketTimeoutException in payment-service · staging/)).toBeInTheDocument()
    // Fingerprint and ID stay available, collapsed under technical details.
    const technical = within(summary).getByText('Technical details').closest('details')!
    expect(technical).not.toHaveAttribute('open')
    expect(technical).toHaveTextContent('9910dd1f3cee53f5cd2fb547aac790bfad744ca3f8ccbfde75a98d60a20af30c')
    expect(technical).toHaveTextContent('java.net.SocketTimeoutException')
  })

  it('shows the most recent event with source and a readable stack trace', async () => {
    const longTrace = ['java.lang.IllegalStateException: boom', ...Array.from({ length: 30 }, (_, i) => `\tat com.shop.Step${i}.run(Step${i}.java:${i})`)].join('\n')
    const user = userEvent.setup()
    renderRoute(path, createFakeApi({ getEvent: vi.fn(async () => eventDetail({ stackTrace: longTrace })) }))

    const trace = await screen.findByRole('figure')
    expect(trace).toHaveTextContent('31 lines')
    expect(within(trace).getByLabelText('Stack trace')).not.toHaveTextContent('Step20')
    await user.click(within(trace).getByRole('button', { name: 'Show all 31 lines' }))
    expect(within(trace).getByLabelText('Stack trace')).toHaveTextContent('Step20')
    expect(screen.getByText('com.shop.payment.GatewayClient')).toBeInTheDocument()
  })

  it('lists the recent events with host, and shows their trace and correlation IDs when expanded', async () => {
    const user = userEvent.setup()
    renderRoute(path, createFakeApi({ getEvent: vi.fn(async () => eventDetail({ correlationId: 'order-1001' })) }))

    const table = (await screen.findByRole('heading', { name: 'Error events' })).closest('section')!
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(rows[1]).toHaveTextContent('payment-7c9d4-abcde')
    expect(rows[1]).not.toHaveTextContent('8c5d2f4b0e6a7b12d4f5a6b7c8d9e0f1')

    await user.click(within(rows[1]).getByRole('button', { name: 'Show event details' }))
    const detail = within(table).getAllByRole('row')[2]
    expect(await within(detail).findByText('8c5d2f4b0e6a7b12d4f5a6b7c8d9e0f1')).toBeInTheDocument()
    expect(within(detail).getByText('order-1001')).toBeInTheDocument()
  })

  it('shows the analysis state and links to root cause analysis', async () => {
    renderRoute(path)

    expect(await screen.findByText('High confidence')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Root cause analysis' })).toHaveAttribute('href', `/root-cause/${PAYMENT_INCIDENT_ID}`)
  })

  it('reports a missing incident', async () => {
    renderRoute(path, createFakeApi({ getIncident: vi.fn().mockRejectedValue(notFound('incident-service')) }))

    expect(await screen.findByRole('heading', { name: 'Incident not found' })).toBeInTheDocument()
  })

  it('keeps the incident visible when event details cannot be loaded', async () => {
    renderRoute(path, createFakeApi({ getEvent: vi.fn().mockRejectedValue(unavailable('ingestion-service')) }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ingestion service unavailable')
    expect(screen.getByRole('heading', { name: 'Summary' })).toBeInTheDocument()
  })
})
