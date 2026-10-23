import { FlaskConical, LogOut, Menu, Network, X } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { useApi } from '../api/ApiContext'
import { useAuth } from '../auth/AuthContext'
import { ROLE_LABELS } from '../auth/types'
import { NAV_ITEMS } from '../navigation'

export const APP_VERSION = '1.0.0'

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  return (parts.length > 1 ? parts[0][0] + parts[parts.length - 1][0] : name.slice(0, 2)).toUpperCase()
}

function UserCard() {
  const { session, mode, logout } = useAuth()
  const [signingOut, setSigningOut] = useState(false)
  if (!session) return null
  const { displayName, username, role } = session.user

  return (
    <div className="user-card">
      <span className="user-card__avatar" aria-hidden="true">
        {initials(displayName)}
      </span>
      <span className="user-card__text">
        <strong title={`${displayName} (${username})`}>{displayName}</strong>
        <small>{mode === 'demo' ? 'Demo session' : ROLE_LABELS[role]}</small>
      </span>
      {mode === 'live' && (
        <button
          type="button"
          className="icon-button user-card__sign-out"
          onClick={() => {
            setSigningOut(true)
            void logout()
          }}
          disabled={signingOut}
          aria-label="Sign out"
          title="Sign out"
        >
          <LogOut size={18} aria-hidden="true" />
        </button>
      )}
    </div>
  )
}

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
          <UserCard />
          <div className="sidebar__meta">
            <span className={`env-pill env-pill--${mode}`}>{mode === 'live' ? 'Live data' : 'Demo data'}</span>
            <span>v{APP_VERSION}</span>
          </div>
        </footer>
      </aside>

      <main id="main-content" className="content" tabIndex={-1}>
        {mode === 'demo' && (
          <div className="demo-banner" role="note">
            <FlaskConical size={18} aria-hidden="true" />
            <p>
              <strong>Demo mode.</strong> Interactive preview using synthetic incidents, bundled with the page. No
              backend is connected: nothing is ingested, correlated or analyzed live.
            </p>
          </div>
        )}
        <Outlet />
      </main>
    </div>
  )
}
