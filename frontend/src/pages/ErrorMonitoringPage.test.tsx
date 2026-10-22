import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi, unavailable } from '../test/fakeApi'
import { eventSummary, page } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

describe('ErrorMonitoringPage', () => {
  it('lists events with their service, severity, exception, fingerprint and trace ID', async () => {
    renderRoute('/errors')

    const table = await screen.findByRole('table')
    const row = within(table).getAllByRole('row')[1]
    expect(row).toHaveTextContent('payment-service')
    expect(row).toHaveTextContent('demo-store · staging')
    expect(row).toHaveTextContent('Error')
    expect(row).toHaveTextContent('SocketTimeoutException')
    expect(row).toHaveTextContent('Payment authorization failed')
    expect(row).toHaveTextContent('9910dd1f3cee')
    expect(row).toHaveTextContent('8c5d2f4b0e6a7b12d4f5a6b7c8d9e0f1')
    expect(screen.getByRole('navigation', { name: 'events pages' })).toHaveTextContent('1 events · page 1 of 1')
  })

  it('passes filters to the API and keeps them in the URL', async () => {
    const user = userEvent.setup()
    const { api, router } = renderRoute('/errors')
    await screen.findByRole('table')

    await user.selectOptions(await screen.findByLabelText('Service'), 'checkout-service')
    await user.selectOptions(screen.getByLabelText('Severity'), 'FATAL')
    await user.type(screen.getByLabelText('Search'), 'timed out{Enter}')

    expect(api.listEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({ service: 'checkout-service', severity: 'FATAL', q: 'timed out', page: 0 }),
      expect.anything(),
    )
    expect(router.state.location.search).toContain('service=checkout-service')

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(api.listEvents).toHaveBeenLastCalledWith(
      expect.objectContaining({ service: undefined, severity: undefined, q: undefined }),
      expect.anything(),
    )
  })

  it('shows the stack trace and source of an event on request', async () => {
    const user = userEvent.setup()
    const { api } = renderRoute('/errors')

    await user.click(await screen.findByRole('button', { name: 'Show event details' }))

    expect(api.getEvent).toHaveBeenCalledWith('evt-1', expect.anything())
    expect(await screen.findByRole('figure')).toHaveTextContent('GatewayClient.authorize(GatewayClient.java:74)')
    expect(screen.getByText('com.shop.payment.GatewayClient')).toBeInTheDocument()
  })

  it('moves between pages', async () => {
    const user = userEvent.setup()
    const api = createFakeApi({ listEvents: vi.fn(async () => page([eventSummary()], 0, 25, 60)) })
    renderRoute('/errors', api)

    await user.click(await screen.findByRole('button', { name: /Next/ }))

    expect(api.listEvents).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }), expect.anything())
  })

  it('distinguishes "no events yet" from "no matching events"', async () => {
    const api = createFakeApi({ listEvents: vi.fn(async () => page([], 0, 25)) })
    renderRoute('/errors', api)
    expect(await screen.findByRole('heading', { name: 'No events yet' })).toBeInTheDocument()

    renderRoute('/errors?severity=FATAL', api)
    expect(await screen.findByRole('heading', { name: 'No events match these filters' })).toBeInTheDocument()
  })

  it('explains when the ingestion service is unavailable and retries', async () => {
    const user = userEvent.setup()
    const listEvents = vi.fn().mockRejectedValueOnce(unavailable('ingestion-service')).mockResolvedValue(page([eventSummary()], 0, 25))
    renderRoute('/errors', createFakeApi({ listEvents }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ingestion service unavailable')
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('table')).toBeInTheDocument()
  })
})
