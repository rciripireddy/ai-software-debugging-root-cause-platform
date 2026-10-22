import { describe, expect, it, vi } from 'vitest'
import { ApiError, createHttpClient, hasFields, withQuery } from './http'

const json = (body: unknown, status = 200, contentType = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })

const anyObject = (body: unknown): body is Record<string, unknown> => hasFields(body)

describe('createHttpClient', () => {
  it('returns the parsed body of a successful response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ id: 'a' }))
    const http = createHttpClient(fetchMock, 1000)

    await expect(http.request('incident-service', '/api/incident/x', anyObject)).resolves.toEqual({ id: 'a' })
    expect(fetchMock).toHaveBeenCalledWith('/api/incident/x', expect.objectContaining({ method: 'GET' }))
  })

  it('turns problem details into an ApiError with status, title and correlation ID', async () => {
    const problem = { title: 'Resource not found', status: 404, detail: 'Incident 1 does not exist.', correlationId: 'c-1' }
    const http = createHttpClient(vi.fn().mockResolvedValue(json(problem, 404, 'application/problem+json')), 1000)

    const error = await http.request('incident-service', '/x', anyObject).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ kind: 'http', status: 404, title: 'Resource not found', correlationId: 'c-1' })
    expect((error as ApiError).message).toBe('Incident 1 does not exist.')
    expect((error as ApiError).isNotFound).toBe(true)
  })

  it('treats a 503 problem from the service as unavailable', async () => {
    const http = createHttpClient(
      vi.fn().mockResolvedValue(json({ title: 'Service unavailable', detail: 'Retry later.' }, 503)),
      1000,
    )

    const error = (await http.request('analysis-service', '/x', anyObject).catch((e: unknown) => e)) as ApiError

    expect(error.kind).toBe('http')
    expect(error.isUnavailable).toBe(true)
  })

  it('reports a proxy error page (502 without problem details) as service unavailable', async () => {
    const http = createHttpClient(vi.fn().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 })), 1000)

    const error = (await http.request('analysis-service', '/x', anyObject).catch((e: unknown) => e)) as ApiError

    expect(error.kind).toBe('unavailable')
    expect(error.service).toBe('analysis-service')
    expect(error.isUnavailable).toBe(true)
  })

  it('reports network failures as service unavailable', async () => {
    const http = createHttpClient(vi.fn().mockRejectedValue(new TypeError('Failed to fetch')), 1000)

    const error = (await http.request('ingestion-service', '/x', anyObject).catch((e: unknown) => e)) as ApiError

    expect(error).toMatchObject({ kind: 'unavailable', service: 'ingestion-service' })
    expect(error.message).toBe('ingestion-service could not be reached.')
  })

  it('rejects a 2xx response with an unexpected shape', async () => {
    const http = createHttpClient(vi.fn().mockResolvedValue(new Response('<html>index</html>', { status: 200 })), 1000)

    const error = (await http.request('incident-service', '/x', anyObject).catch((e: unknown) => e)) as ApiError

    expect(error.kind).toBe('invalid-response')
  })

  it('times out slow requests', async () => {
    const neverResolves = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))),
    )
    const http = createHttpClient(neverResolves as unknown as typeof fetch, 20)

    const error = (await http.request('incident-service', '/x', anyObject).catch((e: unknown) => e)) as ApiError

    expect(error.kind).toBe('unavailable')
    expect(error.message).toContain('did not respond')
  })
})

describe('withQuery', () => {
  it('leaves out empty values and repeats array values', () => {
    expect(withQuery('/api/v1/logs', { service: 'payment-service', q: '', severity: undefined, page: 0 })).toBe(
      '/api/v1/logs?service=payment-service&page=0',
    )
    expect(withQuery('/x', { incidentId: ['a', 'b'], limit: 2 })).toBe('/x?incidentId=a&incidentId=b&limit=2')
    expect(withQuery('/x', {})).toBe('/x')
  })
})
