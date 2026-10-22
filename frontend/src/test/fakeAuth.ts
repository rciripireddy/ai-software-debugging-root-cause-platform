import { vi } from 'vitest'
import type { AuthApi } from '../auth/authApi'
import type { Role, Session } from '../auth/types'

export function session(role: Role = 'ADMIN', overrides: Partial<Session> = {}): Session {
  return {
    user: {
      id: `user-${role.toLowerCase()}`,
      username: role === 'ADMIN' ? 'admin' : 'viewer',
      displayName: role === 'ADMIN' ? 'Ada Admin' : 'Vic Viewer',
      role,
    },
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    csrfToken: 'csrf-test-token',
    ...overrides,
  }
}

/** An AuthApi whose methods are vi.fn mocks: no session to restore, and sign-in as ADMIN. */
export function createFakeAuthApi(overrides: Partial<AuthApi> = {}) {
  return {
    mode: 'live' as const,
    restore: vi.fn(async (): Promise<Session | null> => null),
    login: vi.fn(async () => session('ADMIN')),
    logout: vi.fn(async () => {}),
    ...overrides,
  }
}
