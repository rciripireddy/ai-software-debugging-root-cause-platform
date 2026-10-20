import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderRoute } from './test/renderRoute'

describe('dashboard navigation', () => {
  beforeEach(() => {
    // The layout itself makes no requests, but the health page does when navigated to.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  })

  it('renders the overview page with primary navigation', () => {
    renderRoute('/')

    expect(screen.getByRole('heading', { level: 1, name: 'Operational Overview' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    for (const label of ['Overview', 'Error Monitoring', 'Root Cause Analysis', 'Service Health']) {
      expect(within(nav).getByRole('link', { name: new RegExp(label) })).toBeInTheDocument()
    }
  })

  it('marks starter pages as preview and the health page as live', () => {
    renderRoute('/')

    const nav = screen.getByRole('navigation', { name: 'Primary' })
    expect(within(nav).getByRole('link', { name: /Overview/ })).toHaveTextContent('Preview')
    expect(within(nav).getByRole('link', { name: /Service Health/ })).toHaveTextContent('Live')
  })

  it('does not show fabricated metric values on the overview page', () => {
    renderRoute('/')

    expect(screen.getAllByLabelText('No data yet')).toHaveLength(4)
  })

  it('navigates between pages', async () => {
    const user = userEvent.setup()
    renderRoute('/')

    await user.click(screen.getByRole('link', { name: /Error Monitoring/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Error Monitoring' })).toBeInTheDocument()
    expect(screen.getByText('No error events yet')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: /Root Cause Analysis/ }))
    expect(screen.getByRole('heading', { level: 1, name: 'Root Cause Analysis' })).toBeInTheDocument()
  })

  it('shows a not found page for unknown routes', () => {
    renderRoute('/does-not-exist')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
