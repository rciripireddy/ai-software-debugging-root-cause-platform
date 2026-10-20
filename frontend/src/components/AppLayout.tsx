import { Menu, Network, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { NAV_ITEMS } from '../navigation'
import { FeatureStatusBadge } from './FeatureStatusBadge'

export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="app-shell">
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
          {NAV_ITEMS.map(({ to, label, icon: Icon, status }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `nav-link ${isActive ? 'nav-link--active' : ''}`}
              onClick={() => setMenuOpen(false)}
            >
              <Icon size={18} aria-hidden="true" />
              <span className="nav-link__label">{label}</span>
              <FeatureStatusBadge status={status} />
            </NavLink>
          ))}
        </nav>

        <footer className="sidebar__footer">
          <span className="env-pill">Local development</span>
          <span>v0.1.0 · Phase 1</span>
        </footer>
      </aside>

      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
