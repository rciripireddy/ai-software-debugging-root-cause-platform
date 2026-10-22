import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createHttpApi } from '../api/httpApi'
import { renderRoute } from '../test/renderRoute'

function healthResponse(status: string, httpStatus = 200) {
  return new Response(JSON.stringify({ status }), {
    status: httpStatus,
    headers: { 'Content-Type': 'application/json' },
  })
}

// These tests use the real HTTP client, so the health checks go through `fetch`.
function httpApi() {
  return createHttpApi({
    timeoutMs: 1000,
    base: { ingestion: '/api/ingestion', incident: '/api/incident', analysis: '/api/analysis' },
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

    renderRoute('/health', httpApi())

    const ingestion = screen.getByRole('article', { name: 'Ingestion Service' })
    const incident = screen.getByRole('article', { name: 'Incident Service' })
    const analysis = screen.getByRole('article', { name: 'Analysis Service' })

    expect(await within(ingestion).findByText('Healthy')).toBeInTheDocument()
    expect(await within(incident).findByText('Unhealthy')).toBeInTheDocument()
    expect(await within(analysis).findByText('Unreachable')).toBeInTheDocument()
    expect(within(analysis).getByText('Failed to fetch')).toBeInTheDocument()
    expect(within(ingestion).getByText('/api/ingestion/actuator/health')).toBeInTheDocument()
    expect(await screen.findByText('1 of 3 services healthy')).toBeInTheDocument()
  })

  it('re-runs the health checks when refresh is clicked', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(healthResponse('UP')))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderRoute('/health', httpApi())
    await screen.findAllByText('Healthy')
    expect(await screen.findByText('3 of 3 services healthy')).toBeInTheDocument()
    const initialCalls = fetchMock.mock.calls.length

    await user.click(screen.getByRole('button', { name: 'Refresh' }))

    expect(fetchMock.mock.calls.length).toBe(initialCalls + 3)
  })

  it('does not depend on the other APIs', async () => {
    const fetchMock = vi.fn((path: string) =>
      path.endsWith('/health') ? Promise.resolve(healthResponse('UP')) : Promise.reject(new TypeError('unexpected')),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderRoute('/health', httpApi())
    await screen.findAllByText('Healthy')

    expect(fetchMock.mock.calls.every(([path]) => path.endsWith('/health'))).toBe(true)
  })
})
