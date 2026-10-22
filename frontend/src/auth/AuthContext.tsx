import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import type { AuthApi } from './authApi'
import type { SessionStore } from './sessionStore'
import type { Session, SignOutReason } from './types'

export interface AuthContextValue {
  mode: 'live' | 'demo'
  session: Session | null
  reason?: SignOutReason
  /** True until the session cookie has been checked after a (re)load. */
  checking: boolean
  /** Set when the session could not be checked (e.g. incident-service unreachable). */
  restoreError?: unknown
  login(username: string, password: string): Promise<void>
  logout(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

// setTimeout fires immediately for delays above 2^31-1 ms.
const MAX_TIMER_MS = 2_147_483_647

export function AuthProvider({ authApi, store, children }: { authApi: AuthApi; store: SessionStore; children: ReactNode }) {
  const state = useSyncExternalStore(store.subscribe, store.get)
  const [checking, setChecking] = useState(() => store.get().session === null && store.get().reason === undefined)
  const [restoreError, setRestoreError] = useState<unknown>(undefined)

  // After a (re)load, ask the server whether the session cookie is still valid.
  useEffect(() => {
    if (!checking) return
    let active = true
    authApi
      .restore()
      .then((session) => {
        if (active && session) store.signIn(session)
      })
      .catch((error: unknown) => {
        if (active) setRestoreError(error)
      })
      .finally(() => {
        if (active) setChecking(false)
      })
    return () => {
      active = false
    }
  }, [authApi, store, checking])

  // End the session when the access token expires, instead of waiting for a failing request.
  const expiresAt = state.session?.expiresAt
  useEffect(() => {
    if (!expiresAt) return
    const delay = Math.min(Math.max(Date.parse(expiresAt) - Date.now(), 0), MAX_TIMER_MS)
    const timer = setTimeout(() => store.expire(), delay)
    return () => clearTimeout(timer)
  }, [expiresAt, store])

  const value = useMemo<AuthContextValue>(
    () => ({
      mode: authApi.mode,
      session: state.session,
      reason: state.reason,
      checking,
      restoreError,
      login: async (username, password) => {
        store.signIn(await authApi.login(username, password))
        setRestoreError(undefined)
      },
      logout: async () => {
        try {
          await authApi.logout(store.get().session)
        } catch {
          // Signed out here even if the request failed; the cookie then expires on its own.
        }
        store.signOut('signed-out')
      },
    }),
    [authApi, store, state, checking, restoreError],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('useAuth must be used inside <AuthProvider>')
  return auth
}
