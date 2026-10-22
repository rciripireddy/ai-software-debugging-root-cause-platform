import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import { createHttpAuthApi } from './authApi'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const SESSION = {
  user: { id: 'u1', username: 'admin', displayName: 'Ada Admin', role: 'ADMIN' },
  expiresAt: '2026-10-22T16:00:00Z',
  csrfToken: 'csrf-1',
}

function api(fetchImpl: typeof fetch) {
  return createHttpAuthApi({ fetchImpl, timeoutMs: 1000, base: { incident: '/api/incident' } })
}

describe('createHttpAuthApi', () => {
  it('signs in with a JSON body and returns the session without any token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(SESSION))

    await expect(api(fetchMock).login('admin', 'secret password')).resolves.toEqual(SESSION)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/incident/api/v1/auth/login')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    expect(JSON.parse(init.body as string)).toEqual({ username: 'admin', password: 'secret password' })
    expect(init.headers).toMatchObject({ 'Content-Type': 'application/json' })
  })

  it('reports wrong credentials as a 401 ApiError', async () => {
    const problem = { title: 'Invalid credentials', status: 401, detail: 'The username or password is incorrect.' }

    const error = await api(vi.fn().mockResolvedValue(json(problem, 401))).login('a', 'b').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 401, title: 'Invalid credentials' })
  })

  it('restores the session from the cookie, or returns null when there is none', async () => {
    await expect(api(vi.fn().mockResolvedValue(json(SESSION))).restore()).resolves.toEqual(SESSION)
    await expect(api(vi.fn().mockResolvedValue(json({ title: 'Authentication required' }, 401))).restore()).resolves.toBeNull()
    await expect(api(vi.fn().mockRejectedValue(new TypeError('offline'))).restore()).rejects.toMatchObject({
      kind: 'unavailable',
    })
  })

  it('signs out with the CSRF token of the session', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))

    await api(fetchMock).logout({ ...SESSION, user: { ...SESSION.user, role: 'ADMIN' } })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/incident/api/v1/auth/logout')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'X-CSRF-Token': 'csrf-1' })
  })
})
