import { describe, expect, it, vi } from 'vitest'
import { checkHealth } from './health'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('checkHealth', () => {
  it('reports UP when the service responds with status UP', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 'UP' }))

    const result = await checkHealth('/api/ingestion/actuator/health', fetchMock)

    expect(result.status).toBe('UP')
    expect(result.httpStatus).toBe(200)
    expect(result.latencyMs).toBeGreaterThanOrEqual(0)
    expect(fetchMock).toHaveBeenCalledWith('/api/ingestion/actuator/health', expect.any(Object))
  })

  it('reports DOWN when the service responds but is not healthy', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ status: 'DOWN' }, 503))

    const result = await checkHealth('/api/incident/actuator/health', fetchMock)

    expect(result.status).toBe('DOWN')
    expect(result.httpStatus).toBe(503)
  })

  it('reports UNREACHABLE when the proxy returns a non-health response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }))

    const result = await checkHealth('/api/analysis/health', fetchMock)

    expect(result.status).toBe('UNREACHABLE')
    expect(result.error).toBe('No health response (HTTP 502)')
  })

  it('reports UNREACHABLE when the request fails', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    const result = await checkHealth('/api/analysis/health', fetchMock)

    expect(result.status).toBe('UNREACHABLE')
    expect(result.error).toBe('Failed to fetch')
  })
})
