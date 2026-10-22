import { describe, expect, it, vi } from 'vitest'
import { analysis, eventSummary, incidentDetail, page, stats } from '../test/fixtures'
import { ApiError } from './http'
import { createHttpApi } from './httpApi'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function apiWith(...responses: Response[]) {
  const fetchMock = vi.fn()
  responses.forEach((response) => fetchMock.mockResolvedValueOnce(response))
  const api = createHttpApi({
    fetchImpl: fetchMock,
    timeoutMs: 1000,
    base: { ingestion: '/api/ingestion', incident: '/api/incident', analysis: '/api/analysis' },
  })
  return { api, fetchMock, url: (call = 0) => fetchMock.mock.calls[call][0] as string }
}

describe('createHttpApi', () => {
  it('lists events with filters and pagination through the ingestion proxy path', async () => {
    const { api, url } = apiWith(json(page([eventSummary()], 1, 25, 30)))

    const result = await api.listEvents({ service: 'payment-service', severity: 'ERROR', q: 'timed out', page: 1 })

    expect(result.items).toHaveLength(1)
    expect(url()).toBe('/api/ingestion/api/v1/logs?service=payment-service&severity=ERROR&q=timed+out&page=1&size=25')
  })

  it('reads incident stats and details from the incident service', async () => {
    const { api, url } = apiWith(json(stats()), json(incidentDetail()))

    expect((await api.incidentStats(14)).totalIncidents).toBe(17)
    expect(url(0)).toBe('/api/incident/api/v1/incidents/stats?days=14')
    expect((await api.getIncident('abc')).recentEvents).toHaveLength(2)
    expect(url(1)).toBe('/api/incident/api/v1/incidents/abc')
  })

  it('returns null when an incident has no stored analysis', async () => {
    const { api } = apiWith(json({ title: 'Resource not found', status: 404, detail: 'No analysis exists.' }, 404))

    await expect(api.getLatestAnalysis('abc')).resolves.toBeNull()
  })

  it('triggers an analysis with POST', async () => {
    const { api, fetchMock, url } = apiWith(json(analysis({ revision: 3 }), 201))

    const result = await api.analyzeIncident('abc')

    expect(result.revision).toBe(3)
    expect(url()).toBe('/api/analysis/api/v1/analysis/incidents/abc')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST' })
  })

  it('asks for the latest analyses of several incidents in one request, and none for an empty list', async () => {
    const { api, fetchMock, url } = apiWith(json({ items: [] }))

    await expect(api.latestAnalyses([], 10)).resolves.toEqual([])
    expect(fetchMock).not.toHaveBeenCalled()

    await api.latestAnalyses(['a', 'b'], 2)
    expect(url()).toBe('/api/analysis/api/v1/analysis/incidents?incidentId=a&incidentId=b&limit=2')
  })

  it('reports other analysis errors instead of hiding them', async () => {
    const { api } = apiWith(json({ title: 'Service unavailable', detail: 'The database is temporarily unavailable.' }, 503))

    const error = await api.getLatestAnalysis('abc').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).isUnavailable).toBe(true)
  })

  it('rejects a malformed incident page', async () => {
    const { api } = apiWith(json({ items: [{ unexpected: true }], page: 0, size: 20, totalItems: 1, totalPages: 1 }))

    await expect(api.listIncidents({})).rejects.toMatchObject({ kind: 'invalid-response' })
  })
})
