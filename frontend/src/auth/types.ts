// Signed-in user and session, as returned by incident-service's /api/v1/auth endpoints.

export type Role = 'ADMIN' | 'VIEWER'

export interface AuthUser {
  id: string
  username: string
  displayName: string
  role: Role
}

/**
 * The browser never sees the access token: it is kept in an HttpOnly cookie. The session only
 * holds the user, when the token expires and the CSRF token to send with state-changing requests.
 */
export interface Session {
  user: AuthUser
  /** ISO-8601 instant; null for the demo session, which does not expire. */
  expiresAt: string | null
  csrfToken: string
}

/** Why there is no session any more; shown on the sign-in page. */
export type SignOutReason = 'expired' | 'signed-out'

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Administrator',
  VIEWER: 'Viewer (read-only)',
}

export function canRunAnalysis(session: Session | null): boolean {
  return session?.user.role === 'ADMIN'
}
