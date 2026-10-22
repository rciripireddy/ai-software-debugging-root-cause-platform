import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import { createFakeApi } from '../test/fakeApi'
import { createFakeAuthApi, session } from '../test/fakeAuth'
import { renderRoute } from '../test/renderRoute'
import { loginPath, safeNextPath } from './redirect'

const PROTECTED = ['/', '/errors', '/incidents', '/root-cause', '/health']

function http401() {
  return new ApiError({
    kind: 'http',
    service: 'incident-service',
    status: 401,
    title: 'Invalid credentials',
    detail: 'The username or password is incorrect.',
  })
}

async function signIn(username = 'admin', password = 'correct horse battery') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Username'), username)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
  return user
}

describe('protected routes', () => {
  it.each(PROTECTED)('sends a signed-out visitor of %s to the sign-in page', async (path) => {
    const api = createFakeApi()
    const { router } = renderRoute(path, api, { session: null })

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe(path === '/' ? '' : `?next=${encodeURIComponent(path)}`)
    expect(api.listIncidents).not.toHaveBeenCalled()
    expect(api.listEvents).not.toHaveBeenCalled()
  })

  it('keeps a valid session cookie across a reload', async () => {
    const authApi = createFakeAuthApi({ restore: vi.fn(async () => session('VIEWER')) })
    renderRoute('/incidents', createFakeApi(), { session: null, authApi })

    expect(await screen.findByRole('heading', { level: 1, name: 'Incidents' })).toBeInTheDocument()
    expect(authApi.restore).toHaveBeenCalledTimes(1)
  })
})

describe('sign-in page', () => {
  it('signs in and returns to the page that was originally requested', async () => {
    const authApi = createFakeAuthApi()
    const { router } = renderRoute('/incidents?status=OPEN', createFakeApi(), { session: null, authApi })

    await signIn(' admin ', 'correct horse battery')

    expect(authApi.login).toHaveBeenCalledWith('admin', 'correct horse battery')
    expect(await screen.findByRole('heading', { level: 1, name: 'Incidents' })).toBeInTheDocument()
    expect(router.state.location.pathname + router.state.location.search).toBe('/incidents?status=OPEN')
    const footer = screen.getByText('Ada Admin').closest('.user-card') as HTMLElement
    expect(within(footer).getByText('Administrator')).toBeInTheDocument()
  })

  it('reports wrong credentials, clears only the password and stays on the page', async () => {
    const authApi = createFakeAuthApi({ login: vi.fn().mockRejectedValue(http401()) })
    const { router } = renderRoute('/login', createFakeApi(), { session: null, authApi })

    await signIn('admin', 'wrong password')

    expect(await screen.findByRole('alert')).toHaveTextContent('The username or password is incorrect.')
    expect(screen.getByLabelText('Username')).toHaveValue('admin')
    expect(screen.getByLabelText('Password')).toHaveValue('')
    expect(screen.getByLabelText('Password')).toHaveFocus()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('validates that both fields are filled in before calling the API', async () => {
    const authApi = createFakeAuthApi()
    renderRoute('/login', createFakeApi(), { session: null, authApi })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(screen.getByText('Enter your username.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Username')).toHaveAccessibleDescription('Enter your username.')
    expect(screen.getByLabelText('Username')).toHaveFocus()
    expect(authApi.login).not.toHaveBeenCalled()
  })

  it('uses accessible, password-manager friendly fields', async () => {
    renderRoute('/login', createFakeApi(), { session: null })

    const username = await screen.findByLabelText('Username')
    const password = screen.getByLabelText('Password')
    expect(username).toHaveAttribute('autocomplete', 'username')
    expect(password).toHaveAttribute('type', 'password')
    expect(password).toHaveAttribute('autocomplete', 'current-password')
  })

  it('shows progress while signing in', async () => {
    let finish: () => void = () => {}
    const authApi = createFakeAuthApi({
      login: vi.fn(
        () =>
          new Promise<ReturnType<typeof session>>((resolve) => {
            finish = () => resolve(session('ADMIN'))
          }),
      ),
    })
    renderRoute('/login', createFakeApi(), { session: null, authApi })

    await signIn()

    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled()
    await act(async () => finish())
    expect(await screen.findByRole('heading', { level: 1, name: 'Operational Overview' })).toBeInTheDocument()
  })

  it('explains rate limiting and an unreachable sign-in service', async () => {
    const limited = new ApiError({
      kind: 'http',
      service: 'incident-service',
      status: 429,
      title: 'Too many requests',
      detail: 'Too many failed sign-in attempts. Try again later.',
    })
    const unreachable = new ApiError({
      kind: 'unavailable',
      service: 'incident-service',
      title: 'incident-service unavailable',
      detail: 'incident-service could not be reached.',
    })
    const authApi = createFakeAuthApi({ login: vi.fn().mockRejectedValueOnce(limited).mockRejectedValueOnce(unreachable) })
    renderRoute('/login', createFakeApi(), { session: null, authApi })

    const user = await signIn()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many failed sign-in attempts. Try again later.')

    await user.type(screen.getByLabelText('Password'), 'again')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('incident-service could not be reached')
  })

  it('never redirects to another site after signing in', () => {
    expect(safeNextPath('/incidents?status=OPEN')).toBe('/incidents?status=OPEN')
    for (const unsafe of [null, '', 'https://attacker.example', '//attacker.example', '/\\attacker.example', '/login']) {
      expect(safeNextPath(unsafe)).toBe('/')
    }
    expect(loginPath('/')).toBe('/login')
    expect(loginPath('/errors?q=a b')).toBe('/login?next=%2Ferrors%3Fq%3Da%20b')
  })

  it('sends a signed-in user on to the dashboard', async () => {
    renderRoute('/login?next=%2Fhealth', createFakeApi())

    expect(await screen.findByRole('heading', { level: 1, name: 'Service Health' })).toBeInTheDocument()
  })
})

describe('signing out and expiry', () => {
  it('signs out, clears the session and returns to the sign-in page', async () => {
    const api = createFakeApi()
    const authApi = createFakeAuthApi()
    const { store } = renderRoute('/incidents', api, { authApi })
    const user = userEvent.setup()
    await screen.findByRole('heading', { level: 1, name: 'Incidents' })

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('You have signed out.')
    expect(authApi.logout).toHaveBeenCalledWith(expect.objectContaining({ csrfToken: 'csrf-test-token' }))
    expect(store.get().session).toBeNull()
    const calls = vi.mocked(api.listIncidents).mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(vi.mocked(api.listIncidents).mock.calls.length).toBe(calls)
  })

  it('signs out locally even when the sign-out request fails', async () => {
    const authApi = createFakeAuthApi({ logout: vi.fn().mockRejectedValue(new Error('offline')) })
    const { store } = renderRoute('/', createFakeApi(), { authApi })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(store.get().session).toBeNull()
  })

  it('returns to the sign-in page with a message when the server rejects the session', async () => {
    const { store, router } = renderRoute('/errors?severity=FATAL', createFakeApi())
    await screen.findByRole('heading', { level: 1, name: 'Error Monitoring' })

    // What the HTTP client does when a service answers 401.
    act(() => store.expire())

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Your session has expired. Sign in again to continue.')
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/errors?severity=FATAL')}`)
  })

  it('ends the session when the access token expires', async () => {
    renderRoute('/', createFakeApi(), {
      session: session('VIEWER', { expiresAt: new Date(Date.now() + 1000).toISOString() }),
    })

    expect(await screen.findByRole('heading', { level: 1, name: 'Operational Overview' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument(), {
      timeout: 4000,
    })
    expect(screen.getByRole('status')).toHaveTextContent('Your session has expired.')
  })

  it('shows the viewer role in the account area', async () => {
    renderRoute('/', createFakeApi(), { session: session('VIEWER') })

    const card = (await screen.findByText('Vic Viewer')).closest('.user-card') as HTMLElement
    expect(within(card).getByText('Viewer (read-only)')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
  })
})
