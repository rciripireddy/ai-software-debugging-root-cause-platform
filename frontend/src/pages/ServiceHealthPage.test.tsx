import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderRoute } from '../test/renderRoute'

function healthResponse(status: string, httpStatus = 200) {
  return new Response(JSON.stringify({ status }), {
    status: httpStatus,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('ServiceHealthPage', () => {
  it('shows the live status reported by each backend service', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((path: string) => {
        if (path.startsWith('/api/ingestion')) return Promise.resolve(healthResponse('UP'))
        if (path.startsWith('/api/incident')) return Promise.resolve(healthResponse('DOWN', 503))
        return Promise.reject(new TypeError('Failed to fetch'))
      }),
    )

    renderRoute('/health')

    const ingestion = screen.getByRole('article', { name: 'Ingestion Service' })
    const incident = screen.getByRole('article', { name: 'Incident Service' })
    const analysis = screen.getByRole('article', { name: 'Analysis Service' })

    expect(await within(ingestion).findByText('Healthy')).toBeInTheDocument()
    expect(await within(incident).findByText('Unhealthy')).toBeInTheDocument()
    expect(await within(analysis).findByText('Unreachable')).toBeInTheDocument()
  })

  it('re-runs the health checks when refresh is clicked', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(healthResponse('UP')))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute('/health')
    await screen.findAllByText('Healthy')
    const initialCalls = fetchMock.mock.calls.length

    await user.click(screen.getByRole('button', { name: 'Refresh' }))

    expect(fetchMock.mock.calls.length).toBe(initialCalls + 3)
  })
})
