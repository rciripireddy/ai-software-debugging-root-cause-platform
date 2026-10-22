import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { LoadingState } from '../components/DataState'
import { useAuth } from './AuthContext'
import { loginPath } from './redirect'

/** Shows `children` to signed-in users; everyone else goes to the sign-in page and comes back afterwards. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { session, checking } = useAuth()
  const location = useLocation()

  if (checking) {
    return (
      <div className="auth-screen">
        <LoadingState label="Checking your session…" />
      </div>
    )
  }
  if (!session) return <Navigate to={loginPath(location.pathname + location.search)} replace />
  return children
}
