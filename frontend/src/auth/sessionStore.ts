import type { Session, SignOutReason } from './types'

export interface SessionState {
  session: Session | null
  /** Set when the session ended; cleared by the next sign-in. */
  reason?: SignOutReason
}

/**
 * The current session, shared by the auth provider (React) and the HTTP client (CSRF header,
 * reacting to 401 responses). Kept in memory only: a reload asks the server again.
 */
export interface SessionStore {
  get(): SessionState
  signIn(session: Session): void
  signOut(reason: SignOutReason): void
  /** The server rejected the session (401): ends it, unless it already ended. */
  expire(): void
  subscribe(listener: () => void): () => void
}

export function createSessionStore(initial: Session | null = null): SessionStore {
  let state: SessionState = { session: initial }
  const listeners = new Set<() => void>()
  const set = (next: SessionState) => {
    state = next
    listeners.forEach((listener) => listener())
  }
  return {
    get: () => state,
    signIn: (session) => set({ session }),
    signOut: (reason) => set({ session: null, reason }),
    expire: () => {
      if (state.session) set({ session: null, reason: 'expired' })
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
