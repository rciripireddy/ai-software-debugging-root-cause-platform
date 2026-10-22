import { FlaskConical, Menu, Network, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { useApi } from '../api/ApiContext'
import { NAV_ITEMS } from '../navigation'

export const APP_VERSION = '0.5.0'

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { mode } = useApi()

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <aside className={`sidebar ${menuOpen ? 'sidebar--open' : ''}`}>
        <div className="sidebar__header">
          <div className="brand">
            <span className="brand__mark" aria-hidden="true">
              <Network size={18} />
            </span>
            <span className="brand__text">
              <strong>Root Cause</strong>
              <small>Debugging Platform</small>
            </span>
          </div>
          <button
            type="button"
            className="icon-button sidebar__toggle"
            aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={menuOpen}
            aria-controls="primary-navigation"
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
        </div>

        <nav id="primary-navigation" className="sidebar__nav" aria-label="Primary">
          {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `nav-link ${isActive ? 'nav-link--active' : ''}`}
              onClick={() => setMenuOpen(false)}
            >
              <Icon size={18} aria-hidden="true" />
              <span className="nav-link__label">{label}</span>
            </NavLink>
          ))}
        </nav>

        <footer className="sidebar__footer">
          <span className={`env-pill env-pill--${mode}`}>{mode === 'live' ? 'Live data' : 'Demo data'}</span>
          <span>v{APP_VERSION}</span>
        </footer>
      </aside>

      <main id="main-content" className="content" tabIndex={-1}>
        {mode === 'demo' && (
          <div className="demo-banner" role="note">
            <FlaskConical size={18} aria-hidden="true" />
            <p>
              <strong>Demo mode.</strong> This dashboard shows bundled synthetic data captured from a local run of
              the platform. No backend is connected: nothing is ingested, correlated or analyzed live.
            </p>
          </div>
        )}
        <Outlet />
      </main>
    </div>
  )
}
