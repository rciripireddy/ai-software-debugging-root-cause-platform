import type { AuthApi } from '../auth/authApi'
import type { Session } from '../auth/types'

/**
 * A synthetic session for the demo build: no sign-in, no credentials and no requests. The demo
 * user may use every control, which in demo mode only shows bundled data.
 */
export const DEMO_SESSION: Session = {
  user: { id: 'demo-user', username: 'demo', displayName: 'Demo visitor', role: 'ADMIN' },
  expiresAt: null,
  csrfToken: 'demo',
}

export function createDemoAuthApi(): AuthApi {
  return {
    mode: 'demo',
    restore: async () => DEMO_SESSION,
    login: async () => DEMO_SESSION,
    logout: async () => {},
  }
}
