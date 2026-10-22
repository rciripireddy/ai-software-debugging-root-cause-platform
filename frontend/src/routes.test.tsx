import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderRoute } from './test/renderRoute'

const PAGES = ['Overview', 'Error Monitoring', 'Incidents', 'Root Cause Analysis', 'Service Health']

describe('dashboard navigation', () => {
  it('renders the overview page with primary navigation to every page', () => {
    renderRoute('/')

    expect(screen.getByRole('heading', { level: 1, name: 'Operational Overview' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    for (const label of PAGES) {
      expect(within(nav).getByRole('link', { name: label })).toBeInTheDocument()
    }
  })

  it('marks the current page in the navigation', async () => {
    const user = userEvent.setup()
    renderRoute('/')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')

    await user.click(within(nav).getByRole('link', { name: 'Incidents' }))

    expect(screen.getByRole('heading', { level: 1, name: 'Incidents' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Incidents' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current')
  })

  it('labels live data as live', () => {
    renderRoute('/errors')

    expect(screen.getByText('Live data')).toBeInTheDocument()
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.queryByText(/Demo mode/)).not.toBeInTheDocument()
  })

  it('offers a skip link to the main content', () => {
    renderRoute('/')

    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main-content')
  })

  it('shows a not found page for unknown routes', () => {
    renderRoute('/does-not-exist')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
