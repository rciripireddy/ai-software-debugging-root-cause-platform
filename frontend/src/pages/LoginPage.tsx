import { AlertCircle, Info, LogIn, Network, RefreshCw } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/http'
import { useAuth } from '../auth/AuthContext'
import { safeNextPath } from '../auth/redirect'
import { LoadingState } from '../components/DataState'

interface FieldErrors {
  username?: string
  password?: string
}

function signInErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.isUnavailable) return 'Sign-in is unavailable right now: incident-service could not be reached. Try again shortly.'
    if (error.status === 401) return 'The username or password is incorrect.'
    if (error.status === 429) return error.message || 'Too many failed sign-in attempts. Try again later.'
    if (error.status === 400) return 'Check the username and password and try again.'
  }
  return 'Sign-in failed unexpectedly. Try again.'
}

export function LoginPage() {
  const { session, checking, reason, restoreError, login } = useAuth()
  const [params] = useSearchParams()
  const next = safeNextPath(params.get('next'))
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string>()
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  if (checking) {
    return (
      <div className="auth-screen">
        <LoadingState label="Checking your session…" />
      </div>
    )
  }
  if (session) return <Navigate to={next} replace />

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // Read from the form instead of React state, so the password is not kept after this call.
    const form = new FormData(event.currentTarget)
    const username = String(form.get('username') ?? '').trim()
    const password = String(form.get('password') ?? '')
    const errors: FieldErrors = {
      username: username ? undefined : 'Enter your username.',
      password: password ? undefined : 'Enter your password.',
    }
    setFieldErrors(errors)
    setError(undefined)
    if (errors.username || errors.password) {
      ;(errors.username ? usernameRef : passwordRef).current?.focus()
      return
    }

    setSubmitting(true)
    try {
      await login(username, password)
      // The session is now set; this page re-renders and redirects to `next`.
    } catch (failure) {
      setError(signInErrorMessage(failure))
      if (passwordRef.current) {
        passwordRef.current.value = ''
        passwordRef.current.focus()
      }
    } finally {
      setSubmitting(false)
    }
  }

  const notice =
    reason === 'expired'
      ? 'Your session has expired. Sign in again to continue.'
      : reason === 'signed-out'
        ? 'You have signed out.'
        : undefined

  return (
    <div className="auth-screen">
      <main className="auth-card" aria-labelledby="sign-in-heading">
        <div className="brand brand--auth">
          <span className="brand__mark" aria-hidden="true">
            <Network size={18} />
          </span>
          <span className="brand__text">
            <strong>Root Cause</strong>
            <small>Debugging Platform</small>
          </span>
        </div>
        <h1 id="sign-in-heading">Sign in</h1>
        <p className="muted">Sign in to view errors, incidents and root cause analyses.</p>

        {notice && (
          <p className="auth-notice" role="status">
            <Info size={16} aria-hidden="true" />
            {notice}
          </p>
        )}
        {restoreError !== undefined && !notice && (
          <p className="auth-notice" role="status">
            <Info size={16} aria-hidden="true" />
            Your session could not be checked because incident-service is unavailable. You can still try to sign in.
          </p>
        )}
        {error && (
          <div className="auth-error" role="alert">
            <AlertCircle size={16} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}

        <form className="auth-form" onSubmit={(event) => void submit(event)} noValidate>
          <div className="auth-field">
            <label htmlFor="username">Username</label>
            <input
              ref={usernameRef}
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={100}
              autoFocus
              required
              aria-invalid={fieldErrors.username ? true : undefined}
              aria-describedby={fieldErrors.username ? 'username-error' : undefined}
            />
            {fieldErrors.username && (
              <p id="username-error" className="auth-field__error">
                {fieldErrors.username}
              </p>
            )}
          </div>
          <div className="auth-field">
            <label htmlFor="password">Password</label>
            <input
              ref={passwordRef}
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              maxLength={128}
              required
              aria-invalid={fieldErrors.password ? true : undefined}
              aria-describedby={fieldErrors.password ? 'password-error' : undefined}
            />
            {fieldErrors.password && (
              <p id="password-error" className="auth-field__error">
                {fieldErrors.password}
              </p>
            )}
          </div>
          <button type="submit" className="button button--primary auth-form__submit" disabled={submitting}>
            {submitting ? <RefreshCw size={16} className="spin" aria-hidden="true" /> : <LogIn size={16} aria-hidden="true" />}
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="auth-footnote muted">
          Accounts are created by an administrator. Sessions end after a period of time and when you sign out.
        </p>
      </main>
    </div>
  )
}
