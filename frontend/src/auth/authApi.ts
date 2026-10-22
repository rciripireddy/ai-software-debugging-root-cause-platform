import { ApiError, createHttpClient, hasFields } from '../api/http'
import type { Session } from './types'

/** Sign-in, sign-out and restoring the session after a reload. */
export interface AuthApi {
  readonly mode: 'live' | 'demo'
  /** The current session from the session cookie, or null when signed out or expired. */
  restore(): Promise<Session | null>
  /** @throws ApiError 401 for wrong credentials, 429 after too many failed attempts */
  login(username: string, password: string): Promise<Session>
  logout(session: Session | null): Promise<void>
}

export function isSession(body: unknown): body is Session {
  return (
    hasFields(body, 'user', 'expiresAt', 'csrfToken') &&
    hasFields(body.user, 'id', 'username', 'displayName', 'role') &&
    typeof body.csrfToken === 'string'
  )
}

/** Sign-out answers 204 No Content. */
const isEmpty = (body: unknown): body is undefined => body === undefined

/** Calls incident-service's /api/v1/auth endpoints through the proxy. */
export function createHttpAuthApi({
  fetchImpl = (...args) => fetch(...args),
  timeoutMs,
  base,
}: {
  fetchImpl?: typeof fetch
  timeoutMs: number
  base: { incident: string }
}): AuthApi {
  // No session hooks here: a 401 from these endpoints is an answer, not an expired session.
  const http = createHttpClient(fetchImpl, timeoutMs)
  const auth = (path: string) => `${base.incident}/api/v1/auth${path}`

  return {
    mode: 'live',

    restore: async () => {
      try {
        return await http.request('incident-service', auth('/me'), isSession)
      } catch (error) {
        if (error instanceof ApiError && error.kind === 'http' && error.status === 401) return null
        throw error
      }
    },

    login: (username, password) =>
      http.request('incident-service', auth('/login'), isSession, { method: 'POST', body: { username, password } }),

    logout: async (session) => {
      await http.request('incident-service', auth('/logout'), isEmpty, {
        method: 'POST',
        headers: session ? { 'X-CSRF-Token': session.csrfToken } : {},
      })
    },
  }
}
